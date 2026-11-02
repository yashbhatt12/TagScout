/**
 * The last stock writes moved off the phone:
 *
 *   batchesV1          { opId, action: "findOrCreate", productId, batchNo, mfgDate?, expiryDate? }
 *                        → the batch (any team member: enrollment and GRN use it)
 *                      { opId, action: "setStatus", batchId, status }   (owner + manager)
 *   bumpGrnCounterV1   { opId, reference }  → { sequence | null }        (any team member)
 *   commitCycleCountV1 { opId, warehouseId, rackId, binId, reference,
 *                        rows: [{ epc, sku, classification: "MISSING" | "UNEXPECTED", expectedBinCode? }] }
 *                        → { movementsWritten }                          (any team member)
 *
 * Same rules as the app's BatchRepository, ReferenceGenerator and
 * CycleCountService. Dates are judged in India time (Asia/Kolkata), not by
 * the phone's clock, and cycle-count movements record the person who counted.
 */

import {onCall, HttpsError} from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {FieldValue, Timestamp, type Firestore} from "firebase-admin/firestore";
import {db, REGION} from "./firebase";
import {body, requireMember, type CallerMember} from "./access";
import {isValidEpc, normalizeEpc} from "./epc";
import {javaUrlEncode} from "./products";
import {requireOpId, runOnce} from "./ops";

const ID = /^[A-Za-z0-9_-]{1,128}$/;
const BATCH_NO_MAX = 40;
const BATCH_STATUSES = new Set(["active", "quarantined", "recalled"]); // "expired" is set by the system only
const MAX_COUNT_ROWS = 5000;

function requireId(value: unknown, what: string): string {
  if (typeof value !== "string" || !ID.test(value)) throw new HttpsError("invalid-argument", `Missing ${what}.`);
  return value;
}

function text(value: unknown, max: number): string {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string" || value.trim().length > max) throw new HttpsError("invalid-argument", `Text longer than ${max} characters.`);
  return value.trim();
}

/** Today's date in India, "YYYY-MM-DD". */
export function indiaToday(now = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", {timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit"}).format(now);
}

/** Strict YYYY-MM-DD that is a real calendar date. */
function isIsoDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const d = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === value;
}

/** Same checks and messages as BatchDates.validate in the app. Null when valid. */
export function validateBatchDates(mfg: string, exp: string, today = indiaToday()): string | null {
  if (mfg && !isIsoDate(mfg)) return "Manufacturing date must be YYYY-MM-DD";
  if (exp && !isIsoDate(exp)) return "Expiry date must be YYYY-MM-DD";
  if (mfg && mfg > today) return "Manufacturing date can't be in the future";
  if (mfg && exp && !(exp > mfg)) return "Expiry date must be after manufacturing date";
  return null;
}

const batchKeyOf = (batchNo: string) => batchNo.trim().toLowerCase();
/** Same as BatchRepository.batchIdOf: productId + "__" + URL-encoded batch key. */
export const batchIdOf = (productId: string, batchNo: string) => `${productId}__${javaUrlEncode(batchKeyOf(batchNo))}`;

// ── Batches ───────────────────────────────────────────────────────

export async function findOrCreateBatch(firestore: Firestore, caller: CallerMember, data: Record<string, unknown>) {
  const productId = requireId(data.productId, "product");
  const no = text(data.batchNo, 1000);
  if (!no) throw new HttpsError("invalid-argument", "Batch number is required");
  if (no.length > BATCH_NO_MAX) throw new HttpsError("invalid-argument", `Batch number can be at most ${BATCH_NO_MAX} characters`);
  const mfg = text(data.mfgDate, 20);
  const exp = text(data.expiryDate, 20);
  const dateError = validateBatchDates(mfg, exp);
  if (dateError) throw new HttpsError("invalid-argument", dateError);

  const company = firestore.collection("companies").doc(caller.companyId);
  const product = await company.collection("products").doc(productId).get();
  if (!product.exists) throw new HttpsError("not-found", "That product doesn't exist.");
  const sku = String(product.get("sku") ?? "");
  const ref = company.collection("batches").doc(batchIdOf(productId, no));

  return firestore.runTransaction(async (txn) => {
    const snap = await txn.get(ref);
    if (snap.exists) {
      const existing = {
        batchNo: String(snap.get("batchNo") ?? no),
        mfgDate: String(snap.get("mfgDate") ?? ""),
        expiryDate: String(snap.get("expiryDate") ?? ""),
      };
      const mismatch = (field: string, stored: string, entered: string) => new HttpsError("failed-precondition",
        `Batch ${existing.batchNo} already exists with ${field} ${stored || "not set"}. ` +
        `Dates can't change once a batch exists (you entered ${entered || "nothing"}).`);
      if (mfg && mfg !== existing.mfgDate) throw mismatch("manufacturing date", existing.mfgDate, mfg);
      if (exp && exp !== existing.expiryDate) throw mismatch("expiry date", existing.expiryDate, exp);
      return {
        id: snap.id,
        productId: String(snap.get("productId") ?? productId),
        sku: String(snap.get("sku") ?? sku),
        batchNo: existing.batchNo,
        batchKey: String(snap.get("batchKey") ?? batchKeyOf(no)),
        mfgDate: existing.mfgDate,
        expiryDate: existing.expiryDate,
        status: String(snap.get("status") ?? "active"),
        createdBy: String(snap.get("createdBy") ?? ""),
        updatedBy: String(snap.get("updatedBy") ?? ""),
      };
    }
    txn.set(ref, {
      productId, sku, batchNo: no, batchKey: batchKeyOf(no), mfgDate: mfg, expiryDate: exp,
      status: "active", qtyInStock: 0,
      createdAt: FieldValue.serverTimestamp(), createdBy: caller.uid,
      updatedAt: FieldValue.serverTimestamp(), updatedBy: caller.uid,
    });
    return {
      id: ref.id, productId, sku, batchNo: no, batchKey: batchKeyOf(no), mfgDate: mfg, expiryDate: exp,
      status: "active", createdBy: caller.uid, updatedBy: caller.uid,
    };
  });
}

export const batchesV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const action = String(data.action ?? "");
  if (action !== "findOrCreate" && action !== "setStatus") throw new HttpsError("invalid-argument", "Unknown batch action.");
  const caller = await requireMember(request, action === "setStatus" ? ["owner", "manager"] : undefined);
  const opId = requireOpId(data.opId);
  return runOnce(caller.companyId, opId, `batches.${action}`, caller.uid, async () => {
    if (action === "findOrCreate") {
      const batch = await findOrCreateBatch(db, caller, data);
      logger.info("Batch", {companyId: caller.companyId, uid: caller.uid, batchId: batch.id});
      return batch;
    }
    const batchId = text(data.batchId, 400);
    if (!batchId || batchId.includes("/")) throw new HttpsError("invalid-argument", "Missing batch.");
    const status = String(data.status ?? "");
    if (status === "expired") throw new HttpsError("invalid-argument", "Expired is set automatically");
    if (!BATCH_STATUSES.has(status)) throw new HttpsError("invalid-argument", "Unknown batch status.");
    const ref = db.collection("companies").doc(caller.companyId).collection("batches").doc(batchId);
    if (!(await ref.get()).exists) throw new HttpsError("not-found", "Batch not found");
    await ref.update({status, updatedAt: FieldValue.serverTimestamp(), updatedBy: caller.uid});
    logger.info("Batch status", {companyId: caller.companyId, uid: caller.uid, batchId, status});
    return {id: batchId, status};
  });
});

// ── GRN reference counter ─────────────────────────────────────────

const GRN_PATTERN = /^GRN-(\d{4})-(\d+)$/i;

/**
 * Advances /counters/grn to the reference just used, as the app's
 * bumpGrnCounter did: only GRN-YYYY-NNNN for the current (India) year moves
 * it, and never backwards; anything else is a quiet no-op.
 */
export async function bumpGrnCounter(firestore: Firestore, companyId: string, reference: string, today = indiaToday()) {
  const match = GRN_PATTERN.exec(reference.trim());
  if (!match) return {sequence: null};
  const refYear = Number(match[1]);
  const refSeq = Number(match[2]);
  const currentYear = Number(today.slice(0, 4));
  if (refYear !== currentYear || !Number.isSafeInteger(refSeq)) return {sequence: null};
  const ref = firestore.collection("companies").doc(companyId).collection("counters").doc("grn");
  return firestore.runTransaction(async (txn) => {
    const snap = await txn.get(ref);
    const storedYear = Number(snap.get("year") ?? 0);
    const storedSeq = Number(snap.get("sequence") ?? 0);
    const sequence = storedYear === currentYear ? Math.max(storedSeq, refSeq) : refSeq;
    txn.set(ref, {year: currentYear, sequence, updatedAt: FieldValue.serverTimestamp()}, {merge: true});
    return {sequence};
  });
}

export const bumpGrnCounterV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request);
  const opId = requireOpId(data.opId);
  const reference = text(data.reference, 100);
  return runOnce(caller.companyId, opId, "bumpGrnCounter", caller.uid, () => bumpGrnCounter(db, caller.companyId, reference));
});

// ── Cycle count commit ────────────────────────────────────────────

/**
 * One cyclecount_adjust movement per discrepancy; log-only, units are not
 * changed. MISSING = -1, UNEXPECTED = +1, both at the counted bin.
 */
export async function commitCycleCount(firestore: Firestore, caller: CallerMember, data: Record<string, unknown>) {
  const warehouseId = requireId(data.warehouseId, "location");
  const rackId = requireId(data.rackId, "location");
  const binId = requireId(data.binId, "location");
  const reference = text(data.reference, 100);
  if (!Array.isArray(data.rows)) throw new HttpsError("invalid-argument", "Missing count rows.");
  if (data.rows.length > MAX_COUNT_ROWS) throw new HttpsError("invalid-argument", `At most ${MAX_COUNT_ROWS} rows per count.`);

  const company = firestore.collection("companies").doc(caller.companyId);
  const bin = await company.collection("warehouses").doc(warehouseId).collection("racks").doc(rackId)
    .collection("bins").doc(binId).get();
  if (!bin.exists) throw new HttpsError("not-found", "That location doesn't exist.");
  const binCode = String(bin.get("binCode") ?? "");

  const rows = data.rows.map((raw) => {
    const r = (typeof raw === "object" && raw !== null ? raw : {}) as Record<string, unknown>;
    const epc = normalizeEpc(r.epc);
    if (!isValidEpc(epc)) throw new HttpsError("invalid-argument", "A count row has an invalid EPC.");
    const kind = r.classification;
    if (kind !== "MISSING" && kind !== "UNEXPECTED") throw new HttpsError("invalid-argument", "Rows must be MISSING or UNEXPECTED.");
    return {epc, kind, sku: text(r.sku, 100), expectedBinCode: text(r.expectedBinCode, 50)};
  });
  if (rows.length === 0) return {movementsWritten: 0};

  const now = Timestamp.now();
  for (let i = 0; i < rows.length; i += 450) {
    const batch = firestore.batch();
    for (const row of rows.slice(i, i + 450)) {
      batch.set(company.collection("movements").doc(), {
        type: "cyclecount_adjust",
        productId: "",
        sku: row.sku,
        epc: row.epc,
        quantity: row.kind === "MISSING" ? -1 : 1,
        fromWarehouseId: warehouseId,
        fromRackId: rackId,
        fromBinId: binId,
        fromBinCode: binCode,
        toWarehouseId: warehouseId,
        toRackId: rackId,
        toBinId: binId,
        toBinCode: binCode,
        referenceType: "cyclecount",
        referenceId: reference,
        userId: caller.uid,
        timestamp: now,
        notes: row.kind === "MISSING" ? `Expected at ${binCode}, not physically found` :
          row.expectedBinCode ? `Physically found at ${binCode}, database has it at ${row.expectedBinCode}` :
            `Physically found at ${binCode}, not in database`,
      });
    }
    await batch.commit();
  }
  return {movementsWritten: rows.length};
}

export const commitCycleCountV1 = onCall({region: REGION, timeoutSeconds: 120}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request);
  const opId = requireOpId(data.opId);
  return runOnce(caller.companyId, opId, "commitCycleCount", caller.uid, async () => {
    const result = await commitCycleCount(db, caller, data);
    logger.info("Cycle count committed", {companyId: caller.companyId, uid: caller.uid, ...result});
    return result;
  });
});
