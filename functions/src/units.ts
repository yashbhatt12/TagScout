/**
 * Unit writes after enrollment, done on the server:
 *
 *   changeUnitStatusV1   { opId, epc, status, note? }
 *     → { epc, from, to, changed }
 *   markSoldV1           { opId, epc }                       (checkout)
 *     → { epc, soldAt }   soldAt in epoch milliseconds
 *   updateUnitDetailsV1  { opId, epc, serialNumber?, price?, customFields? }
 *     → { epc }
 *
 * Same behaviour as the app's InventoryRepository.changeUnitStatus,
 * JewelleryCheckoutViewModel.confirmSold and updateUnitDetails, plus the
 * role limits agreed for multi-user:
 *   - staff can't mark a unit lost or disposed, or bring one back from those
 *   - staff can't edit a unit's details (serial, price, custom fields)
 *   - anyone in the team can change other statuses and mark sold
 */

import {onCall, HttpsError} from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {FieldValue, Timestamp, type Firestore} from "firebase-admin/firestore";
import {db, REGION} from "./firebase";
import {body, requireMember, type CallerMember} from "./access";
import {normalizeEpc, isValidEpc} from "./epc";
import {cleanCustomFields} from "./enroll";
import {requireOpId, runOnce} from "./ops";

// ── Statuses (keys match UnitStatus in the app; never rename) ─────

const STATUSES = ["in_stock", "picked", "dispatched", "assigned", "sold", "in_repair", "returned",
  "expired", "lost", "disposed", "retagged"] as const;
type Status = (typeof STATUSES)[number];

/** Set by the system only (nightly expiry, re-tagging). */
const SYSTEM_ONLY = new Set<Status>(["expired", "retagged"]);
/** Counted as stock on hand (STOCK_STATUSES in the app). */
const STOCK = new Set<Status>(["in_stock", "picked"]);
/** Write-offs: owners and managers only, both to set and to undo. */
const WRITE_OFF = new Set<Status>(["lost", "disposed"]);

function asStatus(value: unknown): Status | null {
  return typeof value === "string" && (STATUSES as readonly string[]).includes(value) ? (value as Status) : null;
}

function requireEpc(value: unknown): string {
  const epc = normalizeEpc(value);
  if (!isValidEpc(epc)) throw new HttpsError("invalid-argument", "Not a valid tag EPC.");
  return epc;
}

function note(value: unknown): string {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string" || value.length > 500) throw new HttpsError("invalid-argument", "Note must be text, up to 500 characters.");
  return value.trim();
}

const statusLabel = (s: string) => s.replace(/_/g, " ");

// ── Change status ─────────────────────────────────────────────────

export interface StatusChangeResult {
  epc: string;
  from: string;
  to: string;
  changed: boolean;
}

/**
 *   counted → not counted   outward,  quantity -1, from the unit's location
 *   not counted → counted   inward,   quantity +1, into the unit's location
 *   otherwise               status_change, quantity 0 (history only)
 * Leaving sold clears soldAt/soldBy; entering sold sets them.
 */
export async function changeUnitStatus(
  firestore: Firestore,
  caller: CallerMember,
  epc: string,
  target: Status,
  noteText: string,
): Promise<StatusChangeResult> {
  if (SYSTEM_ONLY.has(target)) throw new HttpsError("invalid-argument", `${statusLabel(target)} is set automatically.`);
  const isStaff = caller.role === "staff";
  if (isStaff && WRITE_OFF.has(target)) {
    throw new HttpsError("permission-denied", `Only an owner or manager can mark a unit ${statusLabel(target)}.`);
  }

  const company = firestore.collection("companies").doc(caller.companyId);
  const unitRef = company.collection("inventory_units").doc(epc);
  const movementRef = company.collection("movements").doc();
  const now = Timestamp.now();

  return firestore.runTransaction(async (txn) => {
    const snap = await txn.get(unitRef);
    if (!snap.exists) throw new HttpsError("not-found", "This tag is no longer enrolled.");
    const from = asStatus(snap.get("status")) ?? "in_stock";
    if (from === target) return {epc, from, to: target, changed: false};
    if (from === "retagged") throw new HttpsError("failed-precondition", "A re-tagged unit can't change status.");
    if (isStaff && WRITE_OFF.has(from)) {
      throw new HttpsError("permission-denied", `Only an owner or manager can change a ${statusLabel(from)} unit.`);
    }

    const wasCounted = STOCK.has(from);
    const isCounted = STOCK.has(target);
    const leaving = wasCounted && !isCounted;
    const arriving = !wasCounted && isCounted;

    const updates: Record<string, unknown> = {status: target, lastMovedAt: now};
    if (target === "sold") {
      updates.soldAt = now;
      updates.soldBy = caller.uid;
    } else if (from === "sold") {
      updates.soldAt = FieldValue.delete();
      updates.soldBy = FieldValue.delete();
    }
    txn.update(unitRef, updates);

    const loc = {
      warehouseId: String(snap.get("currentWarehouseId") ?? ""),
      rackId: String(snap.get("currentRackId") ?? ""),
      binId: String(snap.get("currentBinId") ?? ""),
      binCode: String(snap.get("currentBinCode") ?? ""),
    };
    txn.set(movementRef, {
      type: leaving ? "outward" : arriving ? "inward" : "status_change",
      productId: String(snap.get("productId") ?? ""),
      sku: String(snap.get("sku") ?? ""),
      epc,
      quantity: leaving ? -1 : arriving ? 1 : 0,
      fromWarehouseId: arriving ? "" : loc.warehouseId,
      fromRackId: arriving ? "" : loc.rackId,
      fromBinId: arriving ? "" : loc.binId,
      fromBinCode: arriving ? "" : loc.binCode,
      toWarehouseId: leaving ? "" : loc.warehouseId,
      toRackId: leaving ? "" : loc.rackId,
      toBinId: leaving ? "" : loc.binId,
      toBinCode: leaving ? "" : loc.binCode,
      referenceType: "status_change",
      referenceId: `${from}→${target}`,
      userId: caller.uid,
      timestamp: now,
      notes: noteText,
      batchId: String(snap.get("batchId") ?? ""),
    });
    return {epc, from, to: target, changed: true};
  });
}

// ── Mark sold (checkout) ──────────────────────────────────────────

/** Sells an in-stock piece at checkout. Fails if it was sold elsewhere meanwhile. */
export async function markSold(firestore: Firestore, caller: CallerMember, epc: string): Promise<{ epc: string; soldAt: number }> {
  const company = firestore.collection("companies").doc(caller.companyId);
  const unitRef = company.collection("inventory_units").doc(epc);
  const movementRef = company.collection("movements").doc();
  const now = Timestamp.now();

  // Product (for the SKU and sale type) is read outside the transaction: it isn't changed here.
  const first = await unitRef.get();
  const productId = first.exists ? String(first.get("productId") ?? "") : "";
  const product = productId ? await company.collection("products").doc(productId).get() : null;
  const productSku = product?.exists ? String(product.get("sku") ?? "") : "";

  await firestore.runTransaction(async (txn) => {
    const snap = await txn.get(unitRef);
    const status = snap.exists ? String(snap.get("status") ?? "") : "";
    if (!snap.exists || status !== "in_stock") {
      throw new HttpsError("failed-precondition", status === "sold" ? "This piece was just sold elsewhere." : "This piece is no longer in stock.");
    }
    txn.update(unitRef, {status: "sold", soldAt: now, soldBy: caller.uid, lastMovedAt: now});
    txn.set(movementRef, {
      type: "outward",
      productId: String(snap.get("productId") ?? ""),
      sku: productSku || String(snap.get("sku") ?? ""),
      epc,
      quantity: -1,
      fromWarehouseId: String(snap.get("currentWarehouseId") ?? ""),
      fromRackId: String(snap.get("currentRackId") ?? ""),
      fromBinId: String(snap.get("currentBinId") ?? ""),
      fromBinCode: String(snap.get("currentBinCode") ?? ""),
      toWarehouseId: "",
      toRackId: "",
      toBinId: "",
      toBinCode: "",
      // Pilot pieces have no product; keep their old sale type.
      referenceType: product?.exists ? "retail_sale" : "jewellery_sale",
      referenceId: String(snap.get("itemCode") ?? ""),
      userId: caller.uid,
      timestamp: now,
      notes: "Sold at checkout",
      batchId: String(snap.get("batchId") ?? ""),
    });
  });
  return {epc, soldAt: now.toMillis()};
}

// ── Edit details ──────────────────────────────────────────────────

/**
 * [customFields] is the complete map to store (the app's FieldValidator
 * output, which keeps values of inactive fields). A null price removes it.
 */
export async function updateUnitDetails(
  firestore: Firestore,
  caller: CallerMember,
  epc: string,
  data: Record<string, unknown>,
): Promise<{ epc: string }> {
  if (caller.role === "staff") throw new HttpsError("permission-denied", "Only an owner or manager can edit unit details.");
  const serial = data.serialNumber;
  if (serial !== undefined && serial !== null && (typeof serial !== "string" || serial.length > 100)) {
    throw new HttpsError("invalid-argument", "Serial number must be text, up to 100 characters.");
  }
  const price = data.price;
  if (price !== undefined && price !== null && (typeof price !== "number" || !Number.isFinite(price) || price < 0)) {
    throw new HttpsError("invalid-argument", "Price must be a number of zero or more.");
  }
  const customFields = cleanCustomFields(data.customFields);

  const unitRef = firestore.collection("companies").doc(caller.companyId).collection("inventory_units").doc(epc);
  const snap = await unitRef.get();
  if (!snap.exists) throw new HttpsError("not-found", "This tag is no longer enrolled.");
  if (snap.get("status") === "retagged") throw new HttpsError("failed-precondition", "A re-tagged unit can't be edited.");

  await unitRef.update({
    serialNumber: typeof serial === "string" ? serial.trim() : "",
    price: typeof price === "number" ? price : FieldValue.delete(),
    customFields,
  });
  return {epc};
}

// ── Callables ─────────────────────────────────────────────────────

export const changeUnitStatusV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request);
  const opId = requireOpId(data.opId);
  const epc = requireEpc(data.epc);
  const target = asStatus(data.status);
  if (!target) throw new HttpsError("invalid-argument", "Unknown status.");
  const noteText = note(data.note);
  return runOnce(caller.companyId, opId, "changeUnitStatus", caller.uid, async () => {
    const result = await changeUnitStatus(db, caller, epc, target, noteText);
    logger.info("Unit status", {companyId: caller.companyId, uid: caller.uid, ...result});
    return result;
  });
});

export const markSoldV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request);
  const opId = requireOpId(data.opId);
  const epc = requireEpc(data.epc);
  return runOnce(caller.companyId, opId, "markSold", caller.uid, async () => {
    const result = await markSold(db, caller, epc);
    logger.info("Unit sold", {companyId: caller.companyId, uid: caller.uid, epc});
    return result;
  });
});

export const updateUnitDetailsV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request);
  const opId = requireOpId(data.opId);
  const epc = requireEpc(data.epc);
  return runOnce(caller.companyId, opId, "updateUnitDetails", caller.uid, async () => {
    const result = await updateUnitDetails(db, caller, epc, data);
    logger.info("Unit details", {companyId: caller.companyId, uid: caller.uid, epc});
    return result;
  });
});
