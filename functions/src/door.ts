/**
 * Door (fixed reader) processing: tag reads → passes with a direction and a
 * verdict → alerts for unpaid items leaving.
 *
 * Input: the edge service (TagScoutEdge) writes one document per tag per
 * antenna to /companies/{companyId}/tag_events after de-duplicating raw reads:
 *   { epc, antennaPort, rssi, firstSeenAt, lastSeenAt (ms), readerId, ... }
 *
 * Reader settings: /companies/{companyId}/readers/{readerId}
 *   { name, location, insideAntennas: [1], outsideAntennas: [2], windowMs, enabled }
 * managed with readersV1 (owners and managers). A reader without settings,
 * or with antennas on only one side, gives direction "passed" (no in/out).
 *
 * Output: /companies/{companyId}/door_passes/{passId}, one per crossing:
 *   direction  out    seen inside first, then outside
 *              in     seen outside first, then inside
 *              unknown  only one side seen so far (two-sided reader): not left yet
 *              passed   single-sided reader: crossed, way unknown
 *   verdict    alarm      an unsold piece went out (or passed a single-sided reader)
 *              ok         sold or otherwise allowed to leave, or coming in
 *              unknown_tag  EPC not enrolled
 *              pending    direction not known yet
 * and an alert (/alerts, the format the app's Manager Alerts screen reads)
 * for each alarm. Reads of the same tag at the same reader within windowMs
 * belong to one pass; a per-tag lock (/door_state) keeps simultaneous reads
 * from both antennas on one pass.
 *
 * Replaces alarmNotifier, which alarmed on any read, including items coming in.
 */

import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {onCall, HttpsError} from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {FieldValue, type DocumentReference, type Firestore} from "firebase-admin/firestore";
import {db, REGION} from "./firebase";
import {body, requireMember} from "./access";
import {normalizeEpc} from "./epc";
import {requireOpId, runOnce} from "./ops";

export const DEFAULT_WINDOW_MS = 10_000;
/** Unit statuses that mean "still our stock": leaving without being sold is an alarm. */
const STOCK_STATUSES = new Set(["in_stock", "picked"]);

export type Side = "inside" | "outside" | "any";
export type Direction = "in" | "out" | "unknown" | "passed";
export type Verdict = "alarm" | "ok" | "unknown_tag" | "pending";

export interface ReaderSettings {
  name: string;
  location: string;
  insideAntennas: number[];
  outsideAntennas: number[];
  windowMs: number;
  enabled: boolean;
}

export interface UnitSnapshot {
  status: string;
  sku: string;
  productId: string;
  productTitle: string;
  category: string;
  price: number | null;
}

const asPorts = (raw: unknown): number[] =>
  Array.isArray(raw) ? [...new Set(raw.filter((n): n is number => Number.isInteger(n) && n >= 1 && n <= 64))] : [];

export function readReaderSettings(data: Record<string, unknown> | undefined, readerId: string): ReaderSettings {
  const windowMs = typeof data?.windowMs === "number" && data.windowMs >= 1000 && data.windowMs <= 120_000 ? data.windowMs : DEFAULT_WINDOW_MS;
  return {
    name: typeof data?.name === "string" && data.name.trim() ? data.name.trim() : readerId,
    location: typeof data?.location === "string" ? data.location.trim() : "",
    insideAntennas: asPorts(data?.insideAntennas),
    outsideAntennas: asPorts(data?.outsideAntennas),
    windowMs,
    enabled: data?.enabled !== false,
  };
}

/** Which side of the door an antenna faces; "any" when the reader isn't set up for direction. */
export function sideOf(settings: ReaderSettings, antenna: number): Side {
  const twoSided = settings.insideAntennas.length > 0 && settings.outsideAntennas.length > 0;
  if (!twoSided) return "any";
  if (settings.insideAntennas.includes(antenna)) return "inside";
  if (settings.outsideAntennas.includes(antenna)) return "outside";
  return "any";
}

/** Direction from the first time each side saw the tag. */
export function directionOf(firstSeen: Partial<Record<Side, number>>): Direction {
  const inside = firstSeen.inside;
  const outside = firstSeen.outside;
  if (inside !== undefined && outside !== undefined) return inside <= outside ? "out" : "in";
  if (inside !== undefined || outside !== undefined) return "unknown";
  return "passed";
}

export function verdictOf(direction: Direction, unit: UnitSnapshot | null): Verdict {
  if (!unit) return "unknown_tag";
  if (direction === "unknown") return "pending";
  if (direction === "in") return "ok";
  return STOCK_STATUSES.has(unit.status) ? "alarm" : "ok"; // out or passed
}

async function unitSnapshot(company: DocumentReference, epc: string): Promise<UnitSnapshot | null> {
  const unit = await company.collection("inventory_units").doc(epc).get();
  if (!unit.exists) return null;
  const productId = String(unit.get("productId") ?? "");
  let productTitle = "";
  let category = String(unit.get("category") ?? "");
  if (productId) {
    const product = await company.collection("products").doc(productId).get();
    productTitle = String(product.get("title") ?? "");
    const categoryId = String(product.get("categoryId") ?? "");
    if (categoryId) category = String((await company.collection("categories").doc(categoryId).get()).get("name") ?? category);
  }
  return {
    status: String(unit.get("status") ?? "in_stock"),
    sku: String(unit.get("sku") ?? unit.get("itemCode") ?? ""),
    productId,
    productTitle,
    category,
    price: typeof unit.get("price") === "number" ? unit.get("price") : null,
  };
}

export interface TagEventInput {
  eventId: string;
  epc: string;
  readerId: string;
  antennaPort: number;
  rssi: number | null;
  firstSeenAt: number;
  lastSeenAt: number;
}

/** Folds one tag read into its pass and raises an alert when the pass first becomes an alarm. */
export async function processTagEvent(firestore: Firestore, companyId: string, ev: TagEventInput) {
  const company = firestore.collection("companies").doc(companyId);
  const readerSnap = await company.collection("readers").doc(ev.readerId).get();
  const settings = readReaderSettings(readerSnap.exists ? readerSnap.data() : undefined, ev.readerId);
  if (!settings.enabled) return null;
  const side = sideOf(settings, ev.antennaPort);
  // Read the piece outside the transaction: it doesn't change here, and a retry re-reads it anyway.
  const unit = await unitSnapshot(company, ev.epc);

  const stateRef = company.collection("door_state").doc(`${ev.readerId}~${ev.epc}`);
  const result = await firestore.runTransaction(async (txn) => {
    const state = await txn.get(stateRef);
    const open = state.exists && Number(state.get("lastSeenAt")) >= ev.firstSeenAt - settings.windowMs;
    const passRef = open ? company.collection("door_passes").doc(String(state.get("passId"))) : company.collection("door_passes").doc();
    const pass = open ? await txn.get(passRef) : null;

    const firstSeen: Partial<Record<Side, number>> = {...(pass?.get("firstSeenBySide") ?? {})};
    firstSeen[side] = Math.min(firstSeen[side] ?? Infinity, ev.firstSeenAt);
    const direction = directionOf(firstSeen);
    const verdict = verdictOf(direction, unit);
    const firstSeenAt = Math.min(Number(pass?.get("firstSeenAt") ?? Infinity), ev.firstSeenAt);
    const lastSeenAt = Math.max(Number(pass?.get("lastSeenAt") ?? 0), ev.lastSeenAt);
    const wasAlarm = pass?.get("verdict") === "alarm";

    txn.set(passRef, {
      epc: ev.epc,
      readerId: ev.readerId,
      readerName: settings.name,
      location: settings.location,
      direction,
      verdict,
      firstSeenBySide: firstSeen,
      antennas: FieldValue.arrayUnion(ev.antennaPort),
      firstSeenAt,
      lastSeenAt,
      eventCount: FieldValue.increment(1),
      peakRssi: Math.max(Number(pass?.get("peakRssi") ?? -999), ev.rssi ?? -999),
      unit,
      updatedAt: FieldValue.serverTimestamp(),
      ...(pass?.exists ? {} : {createdAt: FieldValue.serverTimestamp()}),
    }, {merge: true});
    txn.set(stateRef, {passId: passRef.id, lastSeenAt}, {merge: true});
    return {passId: passRef.id, direction, verdict, newAlarm: verdict === "alarm" && !wasAlarm};
  });

  if (result.newAlarm && unit) {
    // Same fields the app's Manager Alerts screen reads, plus the pass. One alert per pass.
    await company.collection("alerts").doc(`pass_${result.passId}`).create({
      epc: ev.epc,
      itemCode: unit.sku || null,
      category: unit.category || unit.productTitle || null,
      productId: unit.productId || null,
      productTitle: unit.productTitle || null,
      price: unit.price,
      unitStatus: unit.status,
      readerId: ev.readerId,
      readerName: settings.name,
      antennaPort: ev.antennaPort,
      rssi: ev.rssi,
      firstSeenAt: ev.firstSeenAt,
      direction: result.direction,
      passId: result.passId,
      sourceEventId: ev.eventId,
      severity: "high",
      read: false,
      createdAt: FieldValue.serverTimestamp(),
    }).catch((e: { code?: unknown }) => {
      if (e.code !== 6) throw e; // already raised for this pass
    });
  }
  return result;
}

export const doorEventProcessor = onDocumentCreated(
  {document: "companies/{companyId}/tag_events/{eventId}", region: REGION, maxInstances: 20},
  async (event) => {
    const {companyId, eventId} = event.params;
    const data = event.data?.data();
    const epc = normalizeEpc(data?.epc);
    if (!data || !epc) {
      logger.warn("tag_event without an EPC", {companyId, eventId});
      return;
    }
    const firstSeenAt = Number(data.firstSeenAt) || Date.now();
    const result = await processTagEvent(db, companyId, {
      eventId,
      epc,
      readerId: typeof data.readerId === "string" && data.readerId ? data.readerId : "default",
      antennaPort: Number.isInteger(data.antennaPort) ? data.antennaPort : 1,
      rssi: typeof data.rssi === "number" ? data.rssi : null,
      firstSeenAt,
      lastSeenAt: Number(data.lastSeenAt) || firstSeenAt,
    });
    if (result) logger.info("Door pass", {companyId, epc, ...result});
  },
);

// ── Reader settings ───────────────────────────────────────────────

const READER_ID = /^[A-Za-z0-9_-]{1,64}$/;

export const readersV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request, ["owner", "manager"]);
  const opId = requireOpId(data.opId);
  const readerId = typeof data.readerId === "string" ? data.readerId.trim() : "";
  if (!READER_ID.test(readerId)) {
    throw new HttpsError("invalid-argument", "Reader ID: letters, digits, - or _ (it must match the edge PC's reader.id).");
  }
  const ref = db.collection("companies").doc(caller.companyId).collection("readers").doc(readerId);

  return runOnce(caller.companyId, opId, `readers.${data.action}`, caller.uid, async () => {
    if (data.action === "delete") {
      await ref.delete();
      return {readerId};
    }
    if (data.action !== "save") throw new HttpsError("invalid-argument", "Unknown reader action.");
    const inside = asPorts(data.insideAntennas);
    const outside = asPorts(data.outsideAntennas);
    if (inside.some((p) => outside.includes(p))) {
      throw new HttpsError("invalid-argument", "An antenna can't face both inside and outside.");
    }
    if ((inside.length === 0) !== (outside.length === 0)) {
      throw new HttpsError("invalid-argument", "For in/out, give at least one inside and one outside antenna; or leave both empty.");
    }
    const name = typeof data.name === "string" ? data.name.trim() : "";
    if (!name || name.length > 60) throw new HttpsError("invalid-argument", "Give the reader a name (up to 60 characters).");
    const windowMs = data.windowMs === undefined ? DEFAULT_WINDOW_MS : Number(data.windowMs);
    if (!Number.isInteger(windowMs) || windowMs < 1000 || windowMs > 120_000) {
      throw new HttpsError("invalid-argument", "Window must be 1–120 seconds.");
    }
    const location = typeof data.location === "string" ? data.location.trim().slice(0, 100) : "";
    await ref.set({
      name, location, insideAntennas: inside, outsideAntennas: outside, windowMs, enabled: data.enabled !== false,
      updatedAt: FieldValue.serverTimestamp(), updatedBy: caller.uid,
    }, {merge: true});
    logger.info("Reader saved", {companyId: caller.companyId, readerId, inside, outside});
    return {readerId};
  });
});
