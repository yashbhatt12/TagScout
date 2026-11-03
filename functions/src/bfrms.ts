/**
 * Fixed readers that send their reads straight to TagScout (no edge PC):
 * Bluebird BFRMS "HTTP POST" endpoints, and the door-monitoring check.
 *
 * bfrmsIngest — the URL pasted into the reader's web page
 * (Communication → BFRMS → Add Endpoint → HTTP POST):
 *   https://asia-south1-<project>.cloudfunctions.net/bfrmsIngest?c=<companyId>&r=<readerId>[&k=<key>]
 * Authentication: HTTP Basic (User ID = reader ID, Password = key), or the
 * key in the URL when the reader offers no authentication. Only a hash of
 * the key is stored (readers/{readerId}.ingestKeyHash).
 *
 * Each post is BFRMS JSON, one tag or a list:
 *   { "type": "Simple", "timestamp": "2023-10-16T07:43:42Z",
 *     "data": { "epc": "...", "antenna": 1, "peakRssi": -40 } }
 * Reads become /tag_events (source "bfrms"), so doorEventProcessor handles
 * them exactly like the edge PC's. The reader's Report Filter (e.g. 2 s,
 * per antenna) does the de-duplication the edge PC would do. A read of the
 * reader's heartbeat tag only updates lastHeartbeatAt.
 *
 * doorMonitor (every 5 minutes) — a switched-on reader that has gone silent
 * gets one "Door monitoring stopped" alert and monitoring = "stopped"; when
 * it is heard again, monitoring returns to "ok". Silence means: direct
 * readers — no heartbeat-tag read for 5 minutes (readers without a
 * heartbeat tag can't be judged and are skipped); edge-PC readers — the PC's
 * heartbeat is over 5 minutes old, or the reader has been unable to connect
 * for 5 minutes.
 */

import {onRequest} from "firebase-functions/v2/https";
import {onSchedule} from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import {createHash, randomBytes, timingSafeEqual} from "node:crypto";
import {FieldValue, Timestamp, type DocumentSnapshot, type Firestore} from "firebase-admin/firestore";
import {db, REGION} from "./firebase";
import {isValidEpc, normalizeEpc} from "./epc";

export const SILENT_AFTER_MS = 5 * 60_000;
const MAX_READS_PER_POST = 500;
const ID = /^[A-Za-z0-9_-]{1,128}$/;

export const hashKey = (key: string) => createHash("sha256").update(key).digest("hex");
export const newIngestKey = () => randomBytes(24).toString("base64url");

export function ingestUrl(companyId: string, readerId: string, project = process.env.GCLOUD_PROJECT ?? ""): string {
  return `https://${REGION}-${project}.cloudfunctions.net/bfrmsIngest?c=${encodeURIComponent(companyId)}&r=${encodeURIComponent(readerId)}`;
}

function sameHash(a: string, b: string): boolean {
  const x = Buffer.from(a, "hex");
  const y = Buffer.from(b, "hex");
  return x.length === y.length && x.length > 0 && timingSafeEqual(x, y);
}

/** The key from HTTP Basic (password) or the k= query parameter. */
export function presentedKey(authorization: string | undefined, query: Record<string, unknown>): string {
  if (authorization?.startsWith("Basic ")) {
    const decoded = Buffer.from(authorization.slice(6), "base64").toString("utf8");
    const i = decoded.indexOf(":");
    if (i >= 0) return decoded.slice(i + 1);
  }
  return typeof query.k === "string" ? query.k : "";
}

export interface IngestRead {
  epc: string;
  antenna: number;
  rssi: number | null;
  at: number;
}

/** BFRMS JSON → reads. Accepts one message, a list of messages, or "data" as a list. */
export function parseBfrms(body: unknown, receivedAt: number): { reads: IngestRead[]; other: string[] } {
  const reads: IngestRead[] = [];
  const other: string[] = [];
  const messages = Array.isArray(body) ? body : [body];
  for (const m of messages) {
    if (!m || typeof m !== "object") continue;
    const msg = m as Record<string, unknown>;
    if (typeof msg.command === "string") {
      other.push(msg.command);
      continue;
    }
    if (typeof msg.event === "string") {
      other.push(msg.event);
      continue;
    }
    const items = Array.isArray(msg.data) ? msg.data : msg.data ? [msg.data] : [];
    for (const it of items) {
      if (!it || typeof it !== "object") continue;
      const d = it as Record<string, unknown>;
      const epc = normalizeEpc(d.epc);
      if (!isValidEpc(epc)) continue;
      const antenna = Number(d.antenna);
      reads.push({
        epc,
        antenna: Number.isInteger(antenna) && antenna >= 1 && antenna <= 64 ? antenna : 1,
        rssi: typeof d.peakRssi === "number" ? d.peakRssi : null,
        // Reader timestamps have 1-second resolution; arrival order (in ms) keeps in/out sequencing.
        at: receivedAt,
      });
    }
  }
  return {reads: reads.slice(0, MAX_READS_PER_POST), other};
}

/** Handles one authenticated post. Returns how many reads became door reads. */
export async function ingest(firestore: Firestore, companyId: string, reader: DocumentSnapshot, body: unknown, receivedAt: number) {
  const {reads, other} = parseBfrms(body, receivedAt);
  const heartbeatEpc = normalizeEpc(reader.get("heartbeatEpc"));
  const company = firestore.collection("companies").doc(companyId);
  const update: Record<string, unknown> = {lastContactAt: FieldValue.serverTimestamp(), connection: "connected", connectionError: ""};
  const doorReads = reads.filter((r) => r.epc !== heartbeatEpc);
  if (doorReads.length < reads.length) update.lastHeartbeatAt = FieldValue.serverTimestamp();
  if (doorReads.length) update.lastReadAt = FieldValue.serverTimestamp();
  if (other.length) update.lastEvent = other[other.length - 1];

  const batch = firestore.batch();
  for (const r of doorReads) {
    batch.set(company.collection("tag_events").doc(), {
      epc: r.epc, antennaPort: r.antenna, rssi: r.rssi, firstSeenAt: r.at, lastSeenAt: r.at,
      readerId: reader.id, source: "bfrms", serverCreatedAt: FieldValue.serverTimestamp(),
    });
  }
  batch.set(reader.ref, update, {merge: true});
  await batch.commit();
  return doorReads.length;
}

export const bfrmsIngest = onRequest({region: REGION, invoker: "public", maxInstances: 20, concurrency: 80}, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("POST only");
    return;
  }
  const companyId = typeof req.query.c === "string" ? req.query.c : "";
  const readerId = typeof req.query.r === "string" ? req.query.r : "";
  if (!ID.test(companyId) || !ID.test(readerId)) {
    res.status(400).send("Missing c or r");
    return;
  }
  const reader = await db.collection("companies").doc(companyId).collection("readers").doc(readerId).get();
  const key = presentedKey(req.get("authorization") ?? undefined, req.query as Record<string, unknown>);
  if (!reader.exists || reader.get("connectionType") !== "bfrms" || !key || !sameHash(hashKey(key), String(reader.get("ingestKeyHash") ?? ""))) {
    logger.warn("bfrmsIngest refused", {companyId, readerId, known: reader.exists});
    res.status(401).send("Unauthorized");
    return;
  }
  let body: unknown = req.body;
  if (typeof body === "string" || Buffer.isBuffer(body)) {
    try {
      body = JSON.parse(body.toString());
    } catch {
      body = null;
    }
  }
  if (reader.get("enabled") !== true) {
    // Accept (so the reader doesn't retry forever) but don't process while switched off.
    await reader.ref.set({lastContactAt: FieldValue.serverTimestamp()}, {merge: true});
    res.status(200).json({ok: true, ignored: "reader is off"});
    return;
  }
  const count = await ingest(db, companyId, reader, body, Date.now());
  res.status(200).json({ok: true, reads: count});
});

// ── Door monitoring ───────────────────────────────────────────────

const millis = (v: unknown): number | null => (v instanceof Timestamp ? v.toMillis() : typeof v === "number" ? v : null);

/** Whether a switched-on reader is being heard; null = can't tell (direct reader without a heartbeat tag). */
export function heard(reader: Record<string, unknown>, edgeLastSeenMs: number | null, now: number): boolean | null {
  if (reader.connectionType === "bfrms") {
    if (!reader.heartbeatEpc) return null;
    const last = millis(reader.lastHeartbeatAt);
    return last !== null && now - last < SILENT_AFTER_MS;
  }
  if (edgeLastSeenMs === null || now - edgeLastSeenMs >= SILENT_AFTER_MS) return false;
  if (reader.connection === "error") {
    const since = millis(reader.connectionAt);
    return since === null || now - since < SILENT_AFTER_MS;
  }
  return true;
}

export async function checkDoorMonitoring(firestore: Firestore, companyId: string, now = Date.now()) {
  const company = firestore.collection("companies").doc(companyId);
  const readers = (await company.collection("readers").get()).docs.filter((r) => r.get("enabled") === true);
  if (!readers.length) return {checked: 0, stopped: 0, resumed: 0};
  const edges = new Map((await company.collection("edges").get()).docs.map((e) => [e.id, millis(e.get("lastSeenAt"))]));
  let stopped = 0;
  let resumed = 0;
  for (const r of readers) {
    const data = r.data() ?? {};
    const ok = heard(data, edges.get(String(data.edgeId ?? "")) ?? null, now);
    if (ok === null) continue;
    const wasStopped = data.monitoring === "stopped";
    if (!ok && !wasStopped) {
      const name = String(data.name || r.id);
      const store = String(data.storeName || "");
      await company.collection("alerts").doc(`mon_${r.id}_${now}`).set({
        kind: "monitoring", epc: "", itemCode: null, category: "Door monitoring stopped",
        productTitle: `Door monitoring stopped${store ? ` at ${store}` : ""}`,
        readerId: r.id, readerName: name, storeId: data.storeId || null, storeName: store || null,
        severity: "high", read: false, createdAt: FieldValue.serverTimestamp(),
      });
      await r.ref.set({monitoring: "stopped", monitoringSince: now}, {merge: true});
      stopped++;
    } else if (ok && wasStopped) {
      await r.ref.set({monitoring: "ok", monitoringSince: now}, {merge: true});
      resumed++;
    } else if (ok && data.monitoring !== "ok") {
      await r.ref.set({monitoring: "ok", monitoringSince: now}, {merge: true});
    }
  }
  return {checked: readers.length, stopped, resumed};
}

export const doorMonitor = onSchedule({schedule: "every 5 minutes", region: REGION, timeZone: "Asia/Kolkata"}, async () => {
  const companies = await db.collection("companies").listDocuments();
  for (const c of companies) {
    try {
      const result = await checkDoorMonitoring(db, c.id);
      if (result.stopped || result.resumed) logger.info("Door monitoring", {companyId: c.id, ...result});
    } catch (e) {
      logger.error("Door monitoring check failed", {companyId: c.id, error: e instanceof Error ? e.message : String(e)});
    }
  }
});
