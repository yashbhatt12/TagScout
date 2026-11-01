/**
 * locationsV1: company set-up of warehouses → racks → bins (shown to each
 * business under its own labels, e.g. Store / Counter / Tray).
 *
 *   { opId, action, ... }
 *   createWarehouse { name, address? }                  → { id }
 *   updateWarehouse { warehouseId, name, address? }
 *   deleteWarehouse { warehouseId }
 *   createRack      { warehouseId, name }               → { id }
 *   updateRack      { warehouseId, rackId, name }
 *   deleteRack      { warehouseId, rackId }
 *   createBin       { warehouseId, rackId, binCode, name? }   → { id }
 *   updateBin       { warehouseId, rackId, binId, name }       (bin code can't change)
 *   deleteBin       { warehouseId, rackId, binId }
 *
 * Same rules as the app's WarehouseRepository: warehouse names unique, rack
 * names unique within a warehouse, bin codes unique across the company (all
 * ignoring case); deleting is refused while any unit is stored there, and a
 * delete takes everything under it. Owners and managers only.
 */

import {onCall, HttpsError} from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {Timestamp, type DocumentReference, type Firestore} from "firebase-admin/firestore";
import {db, REGION} from "./firebase";
import {body, requireMember} from "./access";
import {requireOpId, runOnce} from "./ops";

const ID = /^[A-Za-z0-9_-]{1,128}$/;

function id(value: unknown, what: string): string {
  if (typeof value !== "string" || !ID.test(value)) throw new HttpsError("invalid-argument", `Missing ${what}.`);
  return value;
}

function requiredText(value: unknown, what: string, max: number): string {
  const t = typeof value === "string" ? value.trim() : "";
  if (!t) throw new HttpsError("invalid-argument", `Enter a ${what}.`);
  if (t.length > max) throw new HttpsError("invalid-argument", `${what[0].toUpperCase()}${what.slice(1)} is too long (max ${max}).`);
  return t;
}

function optionalText(value: unknown, max: number): string {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string" || value.trim().length > max) throw new HttpsError("invalid-argument", `Text longer than ${max} characters.`);
  return value.trim();
}

const same = (a: unknown, b: string) => String(a ?? "").trim().toLowerCase() === b.toLowerCase();

async function mustExist(ref: DocumentReference, what: string) {
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError("not-found", `That ${what} doesn't exist any more.`);
  return snap;
}

async function unitsUnder(company: DocumentReference, field: string, value: string): Promise<number> {
  const agg = await company.collection("inventory_units").where(field, "==", value).count().get();
  return agg.data().count;
}

async function deleteAll(firestore: Firestore, refs: DocumentReference[]) {
  for (let i = 0; i < refs.length; i += 450) {
    const batch = firestore.batch();
    refs.slice(i, i + 450).forEach((r) => batch.delete(r));
    await batch.commit();
  }
}

export async function runLocationAction(
  firestore: Firestore,
  companyId: string,
  data: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const company = firestore.collection("companies").doc(companyId);
  const warehouses = company.collection("warehouses");
  const now = Timestamp.now();

  switch (data.action) {
  case "createWarehouse":
  case "updateWarehouse": {
    const name = requiredText(data.name, "name", 100);
    const address = optionalText(data.address, 300);
    const editingId = data.action === "updateWarehouse" ? id(data.warehouseId, "warehouse") : null;
    const all = await warehouses.get();
    if (all.docs.some((d) => same(d.get("name"), name) && d.id !== editingId)) {
      throw new HttpsError("already-exists", `A warehouse named '${name}' already exists`);
    }
    if (editingId) {
      await mustExist(warehouses.doc(editingId), "warehouse");
      await warehouses.doc(editingId).update({name, address, updatedAt: now});
      return {id: editingId};
    }
    const ref = await warehouses.add({name, address, createdAt: now, updatedAt: now});
    return {id: ref.id};
  }

  case "deleteWarehouse": {
    const warehouseId = id(data.warehouseId, "warehouse");
    const warehouseRef = warehouses.doc(warehouseId);
    await mustExist(warehouseRef, "warehouse");
    const units = await unitsUnder(company, "currentWarehouseId", warehouseId);
    if (units > 0) {
      throw new HttpsError("failed-precondition", `Cannot delete warehouse — ${units} unit(s) still stored under it. Move or dispatch them first.`);
    }
    const refs: DocumentReference[] = [];
    for (const rack of (await warehouseRef.collection("racks").get()).docs) {
      (await rack.ref.collection("bins").get()).docs.forEach((b) => refs.push(b.ref));
      refs.push(rack.ref);
    }
    refs.push(warehouseRef);
    await deleteAll(firestore, refs);
    return {id: warehouseId};
  }

  case "createRack":
  case "updateRack": {
    const warehouseId = id(data.warehouseId, "warehouse");
    const name = requiredText(data.name, "name", 100);
    const racks = warehouses.doc(warehouseId).collection("racks");
    await mustExist(warehouses.doc(warehouseId), "warehouse");
    const editingId = data.action === "updateRack" ? id(data.rackId, "rack") : null;
    const all = await racks.get();
    if (all.docs.some((d) => same(d.get("name"), name) && d.id !== editingId)) {
      throw new HttpsError("already-exists", `A rack named '${name}' already exists in this warehouse`);
    }
    if (editingId) {
      await mustExist(racks.doc(editingId), "rack");
      await racks.doc(editingId).update({name});
      return {id: editingId};
    }
    const ref = await racks.add({name, warehouseId, createdAt: now});
    return {id: ref.id};
  }

  case "deleteRack": {
    const warehouseId = id(data.warehouseId, "warehouse");
    const rackId = id(data.rackId, "rack");
    const rackRef = warehouses.doc(warehouseId).collection("racks").doc(rackId);
    await mustExist(rackRef, "rack");
    const units = await unitsUnder(company, "currentRackId", rackId);
    if (units > 0) {
      throw new HttpsError("failed-precondition", `Cannot delete rack — ${units} unit(s) still stored under it. Move or dispatch them first.`);
    }
    const refs = (await rackRef.collection("bins").get()).docs.map((b) => b.ref);
    refs.push(rackRef);
    await deleteAll(firestore, refs);
    return {id: rackId};
  }

  case "createBin": {
    const warehouseId = id(data.warehouseId, "warehouse");
    const rackId = id(data.rackId, "rack");
    const binCode = requiredText(data.binCode, "bin code", 50);
    const name = optionalText(data.name, 100) || binCode;
    const rackRef = warehouses.doc(warehouseId).collection("racks").doc(rackId);
    await mustExist(rackRef, "rack");
    // Bin codes are unique across the whole company.
    for (const wh of (await warehouses.get()).docs) {
      for (const rack of (await wh.ref.collection("racks").get()).docs) {
        const hit = (await rack.ref.collection("bins").get()).docs.find((b) => same(b.get("binCode"), binCode));
        if (hit) {
          throw new HttpsError("already-exists",
            `A bin with code '${binCode}' already exists (in ${wh.get("name") ?? ""} / ${rack.get("name") ?? ""})`);
        }
      }
    }
    const ref = await rackRef.collection("bins").add({binCode, name, rackId, warehouseId, createdAt: now});
    return {id: ref.id};
  }

  case "updateBin": {
    const warehouseId = id(data.warehouseId, "warehouse");
    const rackId = id(data.rackId, "rack");
    const binId = id(data.binId, "bin");
    const name = optionalText(data.name, 100);
    const binRef = warehouses.doc(warehouseId).collection("racks").doc(rackId).collection("bins").doc(binId);
    await mustExist(binRef, "bin");
    await binRef.update({name});
    return {id: binId};
  }

  case "deleteBin": {
    const warehouseId = id(data.warehouseId, "warehouse");
    const rackId = id(data.rackId, "rack");
    const binId = id(data.binId, "bin");
    const binRef = warehouses.doc(warehouseId).collection("racks").doc(rackId).collection("bins").doc(binId);
    await mustExist(binRef, "bin");
    const units = await unitsUnder(company, "currentBinId", binId);
    if (units > 0) {
      throw new HttpsError("failed-precondition", `Cannot delete bin — ${units} unit(s) still stored here. Move or dispatch them first.`);
    }
    await binRef.delete();
    return {id: binId};
  }

  default:
    throw new HttpsError("invalid-argument", "Unknown location action.");
  }
}

export const locationsV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request, ["owner", "manager"]);
  const opId = requireOpId(data.opId);
  const action = String(data.action ?? "");
  return runOnce(caller.companyId, opId, `locations.${action}`, caller.uid, async () => {
    const result = await runLocationAction(db, caller.companyId, data);
    logger.info("Location set-up", {companyId: caller.companyId, uid: caller.uid, action, ...result});
    return result;
  });
});
