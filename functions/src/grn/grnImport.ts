/**
 * grnImportV1: GRN (goods received) import from an Excel file, on the server.
 *
 * The app (or dashboard) uploads the file to Storage at
 *   companies/{companyId}/imports/{uid}/{name}.xlsx
 * then calls:
 *   { opId, action: "validate", path }            → { rows: [...], errorRowCount, totalRowCount } | { fileError }
 *   { opId, action: "commit", path, reference }   → { inwardedCount, failures: [{ rowNumber, reason }] }
 *
 * The checks are a port of the app's GrnService.validate (same rules, same
 * messages): EPC format and duplicates in the file, SKU, warehouse (name,
 * ignoring case) and bin (code; must be in that warehouse), serial and tag
 * price per the product type, per-piece fields matched by column header,
 * batch number and dates, and EPCs already in stock. Commit re-checks the
 * same file, saves the clean rows through the enrollment code (grouped by
 * product, bin, batch and note), creates batches as needed, advances the
 * GRN number when everything was saved, and deletes the upload.
 * Any team member may import (staff receive goods).
 */

import {onCall, HttpsError} from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {getStorage} from "firebase-admin/storage";
import type {DocumentData, Firestore} from "firebase-admin/firestore";
import {db, REGION} from "../firebase";
import {body, requireMember, type CallerMember} from "../access";
import {EPC_RULE_TEXT, isValidEpc} from "../epc";
import {enrollUnits, MAX_UNITS_PER_CALL} from "../enroll";
import {batchIdOf, bumpGrnCounter, findOrCreateBatch, validateBatchDates} from "../stock";
import {requireOpId, runOnce} from "../ops";
import {check, CompanyConfig, headerKey, matchesHeader, validateFields} from "./fields";
import {parseGrnWorkbook, type GrnRow} from "./workbook";

export const MAX_GRN_ROWS = 5000;
const BATCH_NO_MAX = 40;
const PRICE_HEADERS = ["Tag price", "Price", "MRP"];
const BATCH_HEADERS = ["Batch number", "Batch no", "Batch", "Lot", "Lot number", "Lot no"];
const MFG_HEADERS = ["Mfg date", "Manufacturing date", "MFD", "Mfd date", "Date of manufacture"];
const EXPIRY_HEADERS = ["Expiry date", "Expiry", "Exp date", "Best before", "Use by"];

export interface ValidatedRow {
  rowNumber: number;
  sku: string;
  epc: string;
  serialNumber: string;
  binCode: string;
  warehouseName: string;
  notes: string;
  errors: string[];
  // Resolved for commit (not sent back to the app)
  productId: string;
  warehouseId: string;
  rackId: string;
  binId: string;
  price: number | null;
  customFields: Record<string, string | number | boolean>;
  batchNo: string;
  mfgDate: string;
  expiryDate: string;
}

interface Product { id: string; sku: string; productType: string }
interface Bin { id: string; binCode: string; rackId: string; warehouseId: string }

/** All bins of the company, by exact code (codes are unique per company). */
async function loadBins(company: FirebaseFirestore.DocumentReference): Promise<Map<string, Bin>> {
  const bins = new Map<string, Bin>();
  for (const wh of (await company.collection("warehouses").get()).docs) {
    for (const rack of (await wh.ref.collection("racks").get()).docs) {
      for (const b of (await rack.ref.collection("bins").get()).docs) {
        const code = String(b.get("binCode") ?? "");
        if (code && !bins.has(code)) {
          bins.set(code, {id: b.id, binCode: code, rackId: String(b.get("rackId") ?? rack.id), warehouseId: String(b.get("warehouseId") ?? wh.id)});
        }
      }
    }
  }
  return bins;
}

export async function validateGrnRows(firestore: Firestore, companyId: string, rows: GrnRow[]): Promise<ValidatedRow[]> {
  const company = firestore.collection("companies").doc(companyId);
  const settings = await company.collection("settings").doc("config").get();
  const config = new CompanyConfig(settings.exists ? settings.data() as Record<string, unknown> : undefined);
  const warehouses = (await company.collection("warehouses").get()).docs.map((d) => ({id: d.id, name: String(d.get("name") ?? "")}));
  const bins = await loadBins(company);

  const productCache = new Map<string, Product | null>();
  const batchCache = new Map<string, DocumentData | null>();
  const fileBatchDates = new Map<string, [string, string]>();

  const epcCounts = new Map<string, number>();
  for (const r of rows) {
    const e = r.epc.trim().toUpperCase();
    if (e) epcCounts.set(e, (epcCounts.get(e) ?? 0) + 1);
  }

  async function productBySku(sku: string): Promise<Product | null> {
    const key = sku.trim().toLowerCase();
    if (!productCache.has(key)) {
      const products = company.collection("products");
      const hit = (await products.where("skuKey", "==", key).limit(1).get()).docs[0] ??
        (await products.where("sku", "==", sku.trim()).limit(1).get()).docs[0];
      productCache.set(key, hit ? {id: hit.id, sku: String(hit.get("sku") ?? ""), productType: String(hit.get("productType") ?? "")} : null);
    }
    return productCache.get(key) ?? null;
  }

  async function checkBatch(type: string, product: Product, batchNo: string, mfg: string, exp: string): Promise<string[]> {
    const errors: string[] = [];
    if (!batchNo) {
      if (config.isCoreRequired(type, "batchNo")) errors.push("Batch number is required");
      else if (mfg || exp) errors.push("Batch number is needed for these dates");
      return errors;
    }
    if (batchNo.length > BATCH_NO_MAX) return [`Batch number can be at most ${BATCH_NO_MAX} characters`];
    const cacheKey = `${product.id}|${batchNo.trim().toLowerCase()}`;
    const seen = fileBatchDates.get(cacheKey);
    if (!seen) fileBatchDates.set(cacheKey, [mfg, exp]);
    else if ((mfg && seen[0] && mfg !== seen[0]) || (exp && seen[1] && exp !== seen[1])) {
      errors.push(`Batch ${batchNo} has different dates on another row of this file`);
    }
    if (!batchCache.has(cacheKey)) {
      const snap = await company.collection("batches").doc(batchIdOf(product.id, batchNo)).get();
      batchCache.set(cacheKey, snap.exists ? snap.data() ?? null : null);
    }
    const stored = batchCache.get(cacheKey);
    if (stored) {
      const storedMfg = String(stored.mfgDate ?? "");
      const storedExp = String(stored.expiryDate ?? "");
      if (mfg && mfg !== storedMfg) errors.push(`Batch ${batchNo} already exists with mfg date ${storedMfg || "not set"}`);
      if (exp && exp !== storedExp) errors.push(`Batch ${batchNo} already exists with expiry date ${storedExp || "not set"}`);
    } else {
      if (!mfg && config.isCoreRequired(type, "mfgDate")) errors.push("Mfg date is required for a new batch");
      if (!exp && config.isCoreRequired(type, "expiryDate")) errors.push("Expiry date is required for a new batch");
      const dateError = validateBatchDates(mfg, exp);
      if (dateError) errors.push(dateError);
    }
    return errors;
  }

  const out: ValidatedRow[] = [];
  for (const r of rows) {
    const errors = [...r.parseErrors];
    const epc = r.epc.trim().toUpperCase();
    if (epc && !isValidEpc(epc)) errors.push(`EPC '${epc}' isn't a valid tag ID (${EPC_RULE_TEXT})`);
    if (epc && (epcCounts.get(epc) ?? 0) > 1) errors.push(`EPC '${epc}' appears in multiple rows of this file`);

    let product: Product | null = null;
    if (r.sku.trim()) {
      product = await productBySku(r.sku);
      if (!product) errors.push(`SKU '${r.sku.trim()}' not found in the product catalog`);
    }

    let warehouse: { id: string; name: string } | null = null;
    if (r.warehouseName.trim()) {
      const name = r.warehouseName.trim().toLowerCase();
      warehouse = warehouses.find((w) => w.name.toLowerCase() === name) ?? null;
      if (!warehouse) errors.push(`Warehouse '${r.warehouseName.trim()}' not found`);
    }

    let bin: Bin | null = null;
    if (r.binCode.trim()) {
      const found = bins.get(r.binCode.trim()) ?? null;
      bin = found && warehouse && found.warehouseId !== warehouse.id ? null : found;
      if (!bin) {
        errors.push(warehouse ? `Bin '${r.binCode.trim()}' not found in warehouse '${warehouse.name}'` : `Bin '${r.binCode.trim()}' not found`);
      }
    }

    let price: number | null = null;
    let customFields: Record<string, string | number | boolean> = {};
    let batchNo = "";
    let mfg = "";
    let exp = "";
    const serial = r.serialNumber.trim();
    if (product) {
      const type = CompanyConfig.typeOf(product.productType);
      const extras = Object.fromEntries(Object.entries(r.extras).map(([h, v]) => [h.trim(), v]));
      const extra = (headers: string[]) => {
        const keys = new Set(headers.map(headerKey));
        const hit = Object.entries(extras).find(([h]) => keys.has(headerKey(h)));
        return hit ? hit[1].trim() : "";
      };

      if (!serial && config.isCoreRequired(type, "serialNumber")) {
        errors.push(`Serial Number is required for ${config.typeDef(type).label}`);
      }

      const priceCheck = check({label: "Tag price", type: "number", required: config.isCoreRequired(type, "price"), options: [], unit: ""},
        extra(PRICE_HEADERS) || null);
      if (priceCheck.error) errors.push(priceCheck.error);
      price = typeof priceCheck.value === "number" && priceCheck.value > 0 ? priceCheck.value : null;
      if (priceCheck.value !== null && price === null) errors.push("Tag price must be more than 0");

      const defs = config.fieldsFor(type, "unit");
      const input: Record<string, unknown> = {};
      for (const [h, v] of Object.entries(extras)) {
        const def = defs.find((d) => matchesHeader(d, h));
        if (def) input[def.key] = v;
      }
      const fields = validateFields(config, type, "unit", input);
      errors.push(...Object.values(fields.errors));
      customFields = fields.values;

      if (config.hasCapability(type, "batchTracking")) {
        batchNo = extra(BATCH_HEADERS);
        const dateDef = (label: string) => ({label, type: "date" as const, required: false, options: [], unit: ""});
        const mfgCheck = check(dateDef("Mfg date"), extra(MFG_HEADERS) || null);
        const expCheck = check(dateDef("Expiry date"), extra(EXPIRY_HEADERS) || null);
        if (mfgCheck.error) errors.push(mfgCheck.error);
        if (expCheck.error) errors.push(expCheck.error);
        mfg = mfgCheck.value ? String(mfgCheck.value) : "";
        exp = expCheck.value ? String(expCheck.value) : "";
        errors.push(...await checkBatch(type, product, batchNo, mfg, exp));
      }
    }

    // Already in stock (only worth a read once the row is otherwise clean)
    if (epc && errors.length === 0) {
      const existing = await company.collection("inventory_units").doc(epc).get();
      if (existing.exists) errors.push(`EPC '${epc}' is already in inventory`);
    }

    out.push({
      rowNumber: r.rowNumber, sku: r.sku, epc, serialNumber: r.serialNumber, binCode: r.binCode, warehouseName: r.warehouseName,
      notes: r.notes, errors, productId: product?.id ?? "", warehouseId: warehouse?.id ?? "", rackId: bin?.rackId ?? "",
      binId: bin?.id ?? "", price, customFields, batchNo, mfgDate: mfg, expiryDate: exp,
    });
  }
  return out;
}

/** Saves the clean rows, grouped like the app's GrnService.commit. */
export async function commitGrnRows(firestore: Firestore, caller: CallerMember, rows: ValidatedRow[], reference: string) {
  const failures: { rowNumber: number; reason: string }[] = [];
  for (const r of rows) if (r.errors.length) failures.push({rowNumber: r.rowNumber, reason: r.errors[0]});
  const ready = rows.filter((r) => r.errors.length === 0 && r.productId);
  if (ready.length === 0) return {inwardedCount: 0, failures, nothingToInward: true};

  const rowByEpc = new Map(ready.map((r) => [r.epc, r]));
  const groups = new Map<string, ValidatedRow[]>();
  for (const r of ready) {
    const key = JSON.stringify([r.productId, r.binId, r.batchNo.trim().toLowerCase(), r.notes.trim()]);
    groups.set(key, [...(groups.get(key) ?? []), r]);
  }

  let inwarded = 0;
  for (const group of groups.values()) {
    const first = group[0];
    let batchId = "";
    if (first.batchNo) {
      const dated = group.find((r) => r.mfgDate || r.expiryDate) ?? first;
      try {
        const batch = await findOrCreateBatch(firestore, caller,
          {productId: first.productId, batchNo: first.batchNo, mfgDate: dated.mfgDate, expiryDate: dated.expiryDate});
        batchId = batch.id;
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        group.forEach((r) => failures.push({rowNumber: r.rowNumber, reason: `Batch ${first.batchNo}: ${msg}`}));
        continue;
      }
    }
    for (let i = 0; i < group.length; i += MAX_UNITS_PER_CALL) {
      const chunk = group.slice(i, i + MAX_UNITS_PER_CALL);
      try {
        const summary = await enrollUnits(firestore, caller.companyId, caller.uid, {
          productId: first.productId, warehouseId: first.warehouseId, rackId: first.rackId, binId: first.binId, batchId,
          referenceType: "grn", referenceId: reference, notes: first.notes.trim(),
          units: chunk.map((r) => ({epc: r.epc, serialNumber: r.serialNumber.trim(), price: r.price, customFields: r.customFields})),
        });
        inwarded += summary.enrolled;
        for (const res of summary.results) {
          if (res.outcome !== "ENROLLED") {
            failures.push({rowNumber: rowByEpc.get(res.epc)?.rowNumber ?? 0, reason: res.message || res.outcome});
          }
        }
      } catch (e) {
        const msg = e instanceof Error ? e.message : "Save failed";
        chunk.forEach((r) => failures.push({rowNumber: r.rowNumber, reason: msg}));
      }
    }
  }
  failures.sort((a, b) => a.rowNumber - b.rowNumber);
  return {inwardedCount: inwarded, failures, nothingToInward: false};
}

// ── Callable ──────────────────────────────────────────────────────

function uploadPath(caller: CallerMember, raw: unknown): string {
  const path = typeof raw === "string" ? raw.trim() : "";
  const prefix = `companies/${caller.companyId}/imports/${caller.uid}/`;
  if (!path.startsWith(prefix) || path.includes("..") || !/\.xlsx$/i.test(path)) {
    throw new HttpsError("invalid-argument", "That isn't one of your uploaded GRN files.");
  }
  return path;
}

async function readUpload(path: string): Promise<Buffer> {
  const file = getStorage().bucket().file(path);
  const [exists] = await file.exists();
  if (!exists) throw new HttpsError("not-found", "The uploaded file is no longer there. Pick the file again.");
  const [buffer] = await file.download();
  return buffer;
}

async function loadAndValidate(caller: CallerMember, path: string) {
  const parsed = await parseGrnWorkbook(await readUpload(path));
  if (parsed.error !== undefined) return {fileError: parsed.error};
  if (parsed.rows.length > MAX_GRN_ROWS) {
    return {fileError: `This file has ${parsed.rows.length} rows; at most ${MAX_GRN_ROWS} per import. Split it into smaller files.`};
  }
  return {rows: await validateGrnRows(db, caller.companyId, parsed.rows)};
}

export const grnImportV1 = onCall({region: REGION, timeoutSeconds: 540, memory: "1GiB"}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request);
  const action = String(data.action ?? "");
  const path = uploadPath(caller, data.path);

  if (action === "validate") {
    // Read-only: no opId needed.
    const result = await loadAndValidate(caller, path);
    if (result.fileError) return {fileError: result.fileError};
    const rows = result.rows!;
    return {
      rows: rows.map((r) => ({rowNumber: r.rowNumber, sku: r.sku, epc: r.epc, serialNumber: r.serialNumber,
        binCode: r.binCode, warehouseName: r.warehouseName, notes: r.notes, errors: r.errors})),
      errorRowCount: rows.filter((r) => r.errors.length).length,
      totalRowCount: rows.length,
    };
  }

  if (action === "commit") {
    const opId = requireOpId(data.opId);
    const reference = typeof data.reference === "string" ? data.reference.trim() : "";
    if (!reference || reference.length > 100) throw new HttpsError("invalid-argument", "Enter a GRN reference number first");
    return runOnce(caller.companyId, opId, "grnImport.commit", caller.uid, async () => {
      const result = await loadAndValidate(caller, path);
      if (result.fileError) throw new HttpsError("failed-precondition", result.fileError);
      const outcome = await commitGrnRows(db, caller, result.rows!, reference);
      if (outcome.nothingToInward) throw new HttpsError("failed-precondition", "Nothing to inward");
      if (outcome.failures.length === 0) await bumpGrnCounter(db, caller.companyId, reference);
      await getStorage().bucket().file(path).delete().catch(() => undefined);
      logger.info("GRN imported", {companyId: caller.companyId, uid: caller.uid, reference,
        inwarded: outcome.inwardedCount, failed: outcome.failures.length});
      return {inwardedCount: outcome.inwardedCount, failures: outcome.failures.slice(0, 500)};
    });
  }

  throw new HttpsError("invalid-argument", "Unknown GRN action.");
});
