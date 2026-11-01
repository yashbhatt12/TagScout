/**
 * enrollUnitsV1: enroll a batch of tags as units of one product in one bin.
 *
 *   { opId, productId, warehouseId, rackId, binId, batchId?,
 *     referenceType?: "enrollment" | "grn", referenceId?, notes?,
 *     units: [{ epc, tid?, serialNumber?, price?, customFields? }] }   (1–500 units)
 *
 *   → { results: [{ epc, outcome, message }], enrolled, skipped }
 *     outcome: ENROLLED | ALREADY_ENROLLED | DUPLICATE_IN_LIST | INVALID_EPC | FAILED
 *
 * Same behaviour as InventoryRepository.enrollUnits in the app: one
 * transaction per tag, a known EPC is never overwritten, and the unit and
 * its inward movement are written together. On top of that the server reads
 * the product's SKU, the bin code and the batch itself (never trusting the
 * phone), stores EPCs uppercase, and checks the caller is still in the team.
 * Any team member may enroll.
 */

import {onCall, HttpsError} from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {Timestamp, type DocumentReference, type Firestore} from "firebase-admin/firestore";
import {db, REGION} from "./firebase";
import {body, requireMember} from "./access";
import {isValidEpc, normalizeEpc} from "./epc";
import {requireOpId, runOnce} from "./ops";

export const MAX_UNITS_PER_CALL = 500;
const PARALLEL = 8;

export type EnrollOutcome = "ENROLLED" | "ALREADY_ENROLLED" | "DUPLICATE_IN_LIST" | "INVALID_EPC" | "FAILED";
export interface EnrollResult {
  epc: string;
  outcome: EnrollOutcome;
  message: string;
}
export interface EnrollSummary {
  results: EnrollResult[];
  enrolled: number;
  skipped: number;
}

interface UnitInput {
  epc: string;
  tid: string;
  serialNumber: string;
  price: number | null;
  customFields: Record<string, string | number | boolean>;
}

export interface EnrollInput {
  productId: string;
  warehouseId: string;
  rackId: string;
  binId: string;
  batchId: string;
  referenceType: "enrollment" | "grn";
  referenceId: string;
  notes: string;
  units: unknown[];
}

// ── Input checks ──────────────────────────────────────────────────

const ID = /^[A-Za-z0-9_-]{1,128}$/;
const FIELD_KEY = /^[A-Za-z0-9_]{1,64}$/;

function requireId(value: unknown, what: string): string {
  if (typeof value !== "string" || !ID.test(value)) throw new HttpsError("invalid-argument", `Pick a ${what} first.`);
  return value;
}

function optionalId(value: unknown, what: string): string {
  if (value === undefined || value === null || value === "") return "";
  return requireId(value, what);
}

function text(value: unknown, max: number): string {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string") throw new HttpsError("invalid-argument", "Expected text.");
  const t = value.trim();
  if (t.length > max) throw new HttpsError("invalid-argument", `Text longer than ${max} characters.`);
  return t;
}

/** Same as the app's cleanCustomFields: drop nulls and blank text. Values must be text, numbers or yes/no. */
export function cleanCustomFields(raw: unknown): Record<string, string | number | boolean> {
  if (raw === undefined || raw === null) return {};
  if (typeof raw !== "object" || Array.isArray(raw)) throw new HttpsError("invalid-argument", "customFields must be an object.");
  const out: Record<string, string | number | boolean> = {};
  const entries = Object.entries(raw as Record<string, unknown>);
  if (entries.length > 60) throw new HttpsError("invalid-argument", "Too many custom fields.");
  for (const [key, value] of entries) {
    if (!FIELD_KEY.test(key)) throw new HttpsError("invalid-argument", `Invalid field name '${key}'.`);
    if (value === null || value === undefined) continue;
    if (typeof value === "string") {
      const t = value.trim();
      if (t.length > 500) throw new HttpsError("invalid-argument", `Field '${key}' is too long.`);
      if (t) out[key] = t;
    } else if (typeof value === "number" && Number.isFinite(value)) {
      out[key] = value;
    } else if (typeof value === "boolean") {
      out[key] = value;
    } else {
      throw new HttpsError("invalid-argument", `Field '${key}' must be text, a number or yes/no.`);
    }
  }
  return out;
}

function readUnit(raw: unknown): UnitInput & { rawEpc: string } {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
    throw new HttpsError("invalid-argument", "Each unit must be an object with an epc.");
  }
  const u = raw as Record<string, unknown>;
  const price = u.price;
  if (price !== undefined && price !== null && (typeof price !== "number" || !Number.isFinite(price) || price < 0)) {
    throw new HttpsError("invalid-argument", "Price must be a number of zero or more.");
  }
  return {
    rawEpc: typeof u.epc === "string" ? u.epc.trim() : "",
    epc: normalizeEpc(u.epc),
    tid: text(u.tid, 128),
    serialNumber: text(u.serialNumber, 100),
    price: typeof price === "number" ? price : null,
    customFields: cleanCustomFields(u.customFields),
  };
}

export function readEnrollInput(data: Record<string, unknown>): EnrollInput {
  const referenceType = data.referenceType ?? "enrollment";
  if (referenceType !== "enrollment" && referenceType !== "grn") {
    throw new HttpsError("invalid-argument", "referenceType must be enrollment or grn.");
  }
  if (!Array.isArray(data.units) || data.units.length === 0) throw new HttpsError("invalid-argument", "No tags to enroll.");
  if (data.units.length > MAX_UNITS_PER_CALL) {
    throw new HttpsError("invalid-argument", `At most ${MAX_UNITS_PER_CALL} tags per call.`);
  }
  return {
    productId: requireId(data.productId, "product"),
    warehouseId: requireId(data.warehouseId, "location"),
    rackId: requireId(data.rackId, "location"),
    binId: requireId(data.binId, "location"),
    batchId: optionalId(data.batchId, "batch"),
    referenceType,
    referenceId: text(data.referenceId, 100),
    notes: text(data.notes, 500),
    units: data.units,
  };
}

// ── Enrollment ────────────────────────────────────────────────────

export async function enrollUnits(
  firestore: Firestore,
  companyId: string,
  userId: string,
  input: EnrollInput,
): Promise<EnrollSummary> {
  const company = firestore.collection("companies").doc(companyId);

  // Shared context, read on the server.
  const [productSnap, binSnap, batchSnap] = await Promise.all([
    company.collection("products").doc(input.productId).get(),
    company.collection("warehouses").doc(input.warehouseId)
      .collection("racks").doc(input.rackId)
      .collection("bins").doc(input.binId).get(),
    input.batchId ? company.collection("batches").doc(input.batchId).get() : Promise.resolve(null),
  ]);
  if (!productSnap.exists) throw new HttpsError("not-found", "That product doesn't exist.");
  if (!binSnap.exists) throw new HttpsError("not-found", "That location doesn't exist.");
  const sku = String(productSnap.get("sku") ?? "");
  const binCode = String(binSnap.get("binCode") ?? "");
  let batch: { id: string; batchNo: string; mfgDate: string; expiryDate: string } | null = null;
  if (batchSnap) {
    if (!batchSnap.exists) throw new HttpsError("not-found", "That batch doesn't exist.");
    if (batchSnap.get("productId") !== input.productId) {
      throw new HttpsError("invalid-argument", `Batch ${batchSnap.get("batchNo") ?? ""} belongs to a different product.`);
    }
    batch = {
      id: batchSnap.id,
      batchNo: String(batchSnap.get("batchNo") ?? ""),
      mfgDate: String(batchSnap.get("mfgDate") ?? ""),
      expiryDate: String(batchSnap.get("expiryDate") ?? ""),
    };
  }

  // Split out bad and repeated EPCs before touching Firestore.
  const seen = new Set<string>();
  const early: EnrollResult[] = [];
  const toWrite: UnitInput[] = [];
  for (const raw of input.units) {
    const unit = readUnit(raw);
    if (!isValidEpc(unit.epc)) {
      early.push({epc: unit.rawEpc, outcome: "INVALID_EPC", message: "Not a valid tag EPC"});
    } else if (seen.has(unit.epc)) {
      early.push({epc: unit.epc, outcome: "DUPLICATE_IN_LIST", message: "Scanned more than once"});
    } else {
      seen.add(unit.epc);
      toWrite.push(unit);
    }
  }

  const written: EnrollResult[] = new Array(toWrite.length);
  let next = 0;
  await Promise.all(
    Array.from({length: Math.min(PARALLEL, toWrite.length)}, async () => {
      while (next < toWrite.length) {
        const i = next++;
        written[i] = await enrollOne(firestore, company, input, toWrite[i], {sku, binCode, batch}, userId);
      }
    }),
  );

  const results = [...early, ...written];
  const enrolled = results.filter((r) => r.outcome === "ENROLLED").length;
  return {results, enrolled, skipped: results.length - enrolled};
}

async function enrollOne(
  firestore: Firestore,
  company: DocumentReference,
  input: EnrollInput,
  unit: UnitInput,
  shared: { sku: string; binCode: string; batch: { id: string; batchNo: string; mfgDate: string; expiryDate: string } | null },
  userId: string,
): Promise<EnrollResult> {
  const unitRef = company.collection("inventory_units").doc(unit.epc);
  const movementRef = company.collection("movements").doc();
  const now = Timestamp.now();
  try {
    const existing = await firestore.runTransaction(async (txn) => {
      const snap = await txn.get(unitRef);
      if (snap.exists) return {sku: String(snap.get("sku") ?? ""), status: String(snap.get("status") ?? "")};

      const unitData: Record<string, unknown> = {
        epc: unit.epc,
        tid: unit.tid,
        productId: input.productId,
        sku: shared.sku,
        serialNumber: unit.serialNumber,
        currentWarehouseId: input.warehouseId,
        currentRackId: input.rackId,
        currentBinId: input.binId,
        currentBinCode: shared.binCode,
        status: "in_stock",
        inwardedAt: now,
        lastMovedAt: now,
        enrolledAt: now,
        enrolledBy: userId,
        unitKind: "item",
        quantity: 1,
        customFields: unit.customFields,
      };
      if (unit.price !== null) unitData.price = unit.price;
      if (shared.batch) {
        unitData.batchId = shared.batch.id;
        unitData.batchNo = shared.batch.batchNo;
        unitData.mfgDate = shared.batch.mfgDate;
        unitData.expiryDate = shared.batch.expiryDate;
      }
      txn.set(unitRef, unitData);

      txn.set(movementRef, {
        type: "inward",
        productId: input.productId,
        sku: shared.sku,
        epc: unit.epc,
        quantity: 1,
        fromWarehouseId: "",
        fromRackId: "",
        fromBinId: "",
        fromBinCode: "",
        toWarehouseId: input.warehouseId,
        toRackId: input.rackId,
        toBinId: input.binId,
        toBinCode: shared.binCode,
        referenceType: input.referenceType,
        referenceId: input.referenceId,
        userId,
        timestamp: now,
        notes: input.notes,
        batchId: shared.batch?.id ?? "",
      });
      return null;
    });

    if (existing) {
      const where = existing.sku ? ` as ${existing.sku}` : "";
      return {epc: unit.epc, outcome: "ALREADY_ENROLLED", message: `Already enrolled${where} (${existing.status || "unknown"})`};
    }
    return {epc: unit.epc, outcome: "ENROLLED", message: ""};
  } catch (e) {
    return {epc: unit.epc, outcome: "FAILED", message: e instanceof Error ? e.message : "Save failed"};
  }
}

// ── Callable ──────────────────────────────────────────────────────

export const enrollUnitsV1 = onCall({region: REGION, timeoutSeconds: 300, memory: "512MiB"}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request);
  const opId = requireOpId(data.opId);
  const input = readEnrollInput(data);

  return runOnce(caller.companyId, opId, "enrollUnits", caller.uid, async () => {
    const summary = await enrollUnits(db, caller.companyId, caller.uid, input);
    logger.info("Units enrolled", {
      companyId: caller.companyId,
      uid: caller.uid,
      productId: input.productId,
      referenceType: input.referenceType,
      enrolled: summary.enrolled,
      skipped: summary.skipped,
    });
    return summary;
  });
});
