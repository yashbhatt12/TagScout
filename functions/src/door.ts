/**
 * Door (fixed reader) processing: tag reads → passes with a direction and a
 * verdict → alerts for unpaid items leaving.
 *
 * Input: the edge service (TagScoutEdge) writes one document per tag per
 * antenna to /companies/{companyId}/tag_events after de-duplicating raw reads:
 *   { epc, antennaPort, rssi, firstSeenAt, lastSeenAt (ms), readerId, ... }
 *
 * Readers: /companies/{companyId}/readers/{readerId}. The edge PC creates
 * them when it finds a reader on its network (host, model, MAC, antenna ports
 * and which are connected); owners and managers then configure them with
 * readersV1: name, store, each antenna as In (shop side), Out (street side)
 * or not used, and on/off. With both an In and an Out antenna, passes get a
 * direction; otherwise "passed". Reads from antennas not in use are ignored.
 *
 * Edge PCs: /companies/{companyId}/edges/{edgeId}, written by the edge PC
 * (heartbeat, scan results); readersV1 "scan" asks one to search its network.
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
  storeId: string;
  storeName: string;
  insideAntennas: number[];
  outsideAntennas: number[];
  /** Antennas in use; reads from others are ignored. Empty = all (readers set up before antenna roles). */
  activeAntennas: number[];
  windowMs: number;
  enabled: boolean;
}

/** One labelled detail of the product or the piece, for alerts and passes. */
export interface Detail {
  label: string;
  value: string;
}

export interface UnitSnapshot {
  status: string;
  sku: string;
  productId: string;
  productTitle: string;
  category: string;
  brand: string;
  imageUrl: string;
  price: number | null;
  serialNumber: string;
  /** Where the piece is stocked: store and bin code. */
  stockedAt: string;
  /** Product details (catalog fields), then the piece's own (weights, purity, batch...), labelled from the company's settings. */
  productDetails: Detail[];
  unitDetails: Detail[];
}

const asPorts = (raw: unknown): number[] =>
  Array.isArray(raw) ? [...new Set(raw.filter((n): n is number => Number.isInteger(n) && n >= 1 && n <= 64))] : [];

export function readReaderSettings(data: Record<string, unknown> | undefined, readerId: string): ReaderSettings {
  const windowMs = typeof data?.windowMs === "number" && data.windowMs >= 1000 && data.windowMs <= 120_000 ? data.windowMs : DEFAULT_WINDOW_MS;
  const inside = asPorts(data?.insideAntennas);
  const outside = asPorts(data?.outsideAntennas);
  return {
    name: typeof data?.name === "string" && data.name.trim() ? data.name.trim() : readerId,
    location: typeof data?.location === "string" ? data.location.trim() : "",
    storeId: typeof data?.storeId === "string" ? data.storeId : "",
    storeName: typeof data?.storeName === "string" ? data.storeName : "",
    insideAntennas: inside,
    outsideAntennas: outside,
    activeAntennas: [...new Set([...inside, ...outside])],
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

const text = (v: unknown): string => {
  if (v === null || v === undefined || v === "") return "";
  if (typeof v === "boolean") return v ? "Yes" : "No";
  if (typeof v === "number") return Number.isInteger(v) ? v.toLocaleString("en-IN") : v.toLocaleString("en-IN", {maximumFractionDigits: 3});
  return String(v);
};

/** Field key → {label, unit} from the company's settings (every type's and group's fields). */
async function fieldLabels(company: DocumentReference): Promise<Map<string, { label: string; unit: string }>> {
  const settings = (await company.collection("settings").doc("config").get()).data() ?? {};
  const labels = new Map<string, { label: string; unit: string }>();
  const add = (fields: unknown) => {
    for (const f of Array.isArray(fields) ? fields : []) {
      if (f && typeof f.key === "string" && !labels.has(f.key)) {
        labels.set(f.key, {label: typeof f.label === "string" ? f.label : f.key, unit: typeof f.unit === "string" ? f.unit : ""});
      }
    }
  };
  for (const t of Object.values((settings.productTypes ?? {}) as Record<string, { fields?: unknown }>)) add(t?.fields);
  for (const g of Array.isArray(settings.attributeGroups) ? settings.attributeGroups : []) add(g?.fields);
  return labels;
}

function detailsOf(fields: unknown, labels: Map<string, { label: string; unit: string }>): Detail[] {
  if (!fields || typeof fields !== "object") return [];
  return Object.entries(fields as Record<string, unknown>)
    .map(([key, v]) => {
      const def = labels.get(key);
      const value = text(v);
      return value ? {label: def?.label ?? key, value: def?.unit && typeof v === "number" ? `${value} ${def.unit}` : value} : null;
    })
    .filter((d): d is Detail => d !== null);
}

async function nameOf(company: DocumentReference, collection: string, id: string): Promise<string> {
  if (!id) return "";
  return String((await company.collection(collection).doc(id).get()).get("name") ?? "");
}

export async function unitSnapshot(company: DocumentReference, epc: string): Promise<UnitSnapshot | null> {
  const unit = await company.collection("inventory_units").doc(epc).get();
  if (!unit.exists) return null;
  const productId = String(unit.get("productId") ?? "");
  const product = productId ? await company.collection("products").doc(productId).get() : null;
  const p = product?.exists ? product : null;
  const [labels, category, brand, storeName] = await Promise.all([
    fieldLabels(company),
    nameOf(company, "categories", String(p?.get("categoryId") ?? "")),
    nameOf(company, "brands", String(p?.get("brandId") ?? "")),
    nameOf(company, "warehouses", String(unit.get("currentWarehouseId") ?? "")),
  ]);
  const binCode = String(unit.get("currentBinCode") ?? "");

  const productDetails: Detail[] = [];
  if (p?.get("shortName")) productDetails.push({label: "Short name", value: String(p.get("shortName"))});
  if (p?.get("hsnCode")) productDetails.push({label: "HSN code", value: String(p.get("hsnCode"))});
  productDetails.push(...detailsOf(p?.get("customFields"), labels));

  const unitDetails: Detail[] = [];
  if (unit.get("serialNumber")) unitDetails.push({label: "Serial number", value: String(unit.get("serialNumber"))});
  if (unit.get("batchNo")) unitDetails.push({label: "Batch", value: String(unit.get("batchNo"))});
  if (unit.get("expiryDate")) unitDetails.push({label: "Expiry date", value: String(unit.get("expiryDate"))});
  unitDetails.push(...detailsOf(unit.get("customFields"), labels));

  return {
    status: String(unit.get("status") ?? "in_stock"),
    sku: String(unit.get("sku") ?? unit.get("itemCode") ?? ""),
    productId,
    productTitle: String(p?.get("title") ?? ""),
    category: category || String(unit.get("category") ?? ""),
    brand,
    imageUrl: String(p?.get("imageUrl") ?? ""),
    price: typeof unit.get("price") === "number" ? unit.get("price") : null,
    serialNumber: String(unit.get("serialNumber") ?? ""),
    stockedAt: [storeName, binCode].filter(Boolean).join(" · "),
    productDetails,
    unitDetails,
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
  if (settings.activeAntennas.length && !settings.activeAntennas.includes(ev.antennaPort)) return null;
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
      storeId: settings.storeId,
      storeName: settings.storeName,
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
      storeId: settings.storeId || null,
      storeName: settings.storeName || null,
      unit,
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

// ── Readers: scan, configure, remove ──────────────────────────────

const ID = /^[A-Za-z0-9_-]{1,128}$/;
const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;
type Role = "in" | "out" | "off";

/**
 * readersV1 (owners and managers):
 *   { action: "scan", edgeId, hosts?: ["192.168.1.111"] }   ask an edge PC to search its network (or check these IPs)
 *   { action: "configure", readerId, name, storeId, antennaRoles: { "1": "in", "2": "out", "3": "off" }, enabled, windowMs? }
 *   { action: "remove", readerId }
 */
export const readersV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request, ["owner", "manager"]);
  const opId = requireOpId(data.opId);
  const company = db.collection("companies").doc(caller.companyId);

  return runOnce(caller.companyId, opId, `readers.${data.action}`, caller.uid, async () => {
    if (data.action === "scan") {
      const edgeId = typeof data.edgeId === "string" && ID.test(data.edgeId) ? data.edgeId : "";
      if (!edgeId) throw new HttpsError("invalid-argument", "Pick an edge PC.");
      const hosts = Array.isArray(data.hosts) ? data.hosts.map((h) => String(h).trim()).filter(Boolean) : [];
      if (hosts.length > 20 || hosts.some((h) => !IPV4.test(h))) throw new HttpsError("invalid-argument", "Enter IP addresses like 192.168.1.111.");
      const edge = company.collection("edges").doc(edgeId);
      if (!(await edge.get()).exists) throw new HttpsError("not-found", "That edge PC hasn't connected yet.");
      const requestId = opId;
      await edge.set({
        scanRequest: {id: requestId, hosts, requestedAt: FieldValue.serverTimestamp(), requestedBy: caller.uid},
        scanStatus: "requested",
        scanError: "",
      }, {merge: true});
      return {edgeId, requestId};
    }

    const readerId = typeof data.readerId === "string" && ID.test(data.readerId) ? data.readerId : "";
    if (!readerId) throw new HttpsError("invalid-argument", "Missing reader.");
    const ref = company.collection("readers").doc(readerId);
    const reader = await ref.get();

    if (data.action === "remove") {
      await ref.delete();
      return {readerId};
    }
    if (data.action !== "configure") throw new HttpsError("invalid-argument", "Unknown reader action.");
    if (!reader.exists) throw new HttpsError("not-found", "Reader not found. Scan the network again.");

    const name = typeof data.name === "string" ? data.name.trim() : "";
    if (!name || name.length > 60) throw new HttpsError("invalid-argument", "Give the reader a name (up to 60 characters).");

    const storeId = typeof data.storeId === "string" ? data.storeId : "";
    let storeName = "";
    if (storeId) {
      if (!ID.test(storeId)) throw new HttpsError("invalid-argument", "Pick a store.");
      const store = await company.collection("warehouses").doc(storeId).get();
      if (!store.exists) throw new HttpsError("not-found", "That store doesn't exist any more.");
      storeName = String(store.get("name") ?? "");
    }

    const ports = new Set(asPorts((reader.get("antennas") ?? []).map((a: { port?: unknown }) => a?.port)));
    const raw = data.antennaRoles && typeof data.antennaRoles === "object" ? data.antennaRoles as Record<string, unknown> : {};
    const roles: Record<string, Role> = {};
    for (const [k, v] of Object.entries(raw)) {
      const port = Number(k);
      if (!ports.has(port)) throw new HttpsError("invalid-argument", `This reader has no antenna port ${k}.`);
      if (v !== "in" && v !== "out" && v !== "off") throw new HttpsError("invalid-argument", "Each antenna is In, Out or Not used.");
      roles[String(port)] = v;
    }
    const inside = Object.entries(roles).filter(([, r]) => r === "in").map(([p]) => Number(p)).sort();
    const outside = Object.entries(roles).filter(([, r]) => r === "out").map(([p]) => Number(p)).sort();

    const enabled = data.enabled === true;
    if (enabled && !storeId) throw new HttpsError("failed-precondition", "Assign the reader to a store before switching it on.");
    if (enabled && inside.length + outside.length === 0) {
      throw new HttpsError("failed-precondition", "Set at least one antenna to In or Out before switching the reader on.");
    }
    const windowMs = data.windowMs === undefined ? DEFAULT_WINDOW_MS : Number(data.windowMs);
    if (!Number.isInteger(windowMs) || windowMs < 1000 || windowMs > 120_000) {
      throw new HttpsError("invalid-argument", "Window must be 1–120 seconds.");
    }

    await ref.set({
      name, storeId, storeName, location: storeName, antennaRoles: roles,
      insideAntennas: inside, outsideAntennas: outside, windowMs, enabled, configured: true,
      updatedAt: FieldValue.serverTimestamp(), updatedBy: caller.uid,
    }, {merge: true});
    logger.info("Reader configured", {companyId: caller.companyId, readerId, storeId, inside, outside, enabled});
    return {readerId};
  });
});
