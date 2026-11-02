/**
 * Door (fixed reader) data: edge PCs, the readers they found, door passes,
 * and the reader actions (scan, configure, remove) via readersV1.
 */
import { limit, onSnapshot, orderBy, query } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { companyCol, functions } from "../firebase";
import { toDate } from "./format";
import type { AlertUnit, Detail } from "./data";

export type Direction = "in" | "out" | "unknown" | "passed";
export type Verdict = "alarm" | "ok" | "unknown_tag" | "pending";
export type AntennaRole = "in" | "out" | "off";

export type DoorPass = {
  id: string;
  epc: string;
  readerId: string;
  readerName: string;
  storeName: string;
  direction: Direction;
  verdict: Verdict;
  antennas: number[];
  firstSeenAt: number;
  lastSeenAt: number;
  eventCount: number;
  unit: (AlertUnit & { sku: string; price: number | null }) | null;
};

export type Edge = {
  id: string;
  name: string;
  hostname: string;
  ips: string[];
  version: string;
  lastSeenAt: Date | null;
  scanStatus: "" | "requested" | "scanning" | "done" | "error";
  scanError: string;
  scanFound: number | null;
  scanCompletedAt: Date | null;
};

export type ReaderDoc = {
  id: string;
  edgeId: string;
  host: string;
  mac: string;
  manufacturer: string;
  modelId: string;
  firmware: string;
  maxAntennas: number;
  antennas: { port: number; connected: boolean }[];
  probeError: string;
  lastProbedAt: Date | null;
  webConsolePort: number | null;
  connection: "" | "connecting" | "connected" | "error" | "stopped";
  connectionError: string;
  lastReadAt: Date | null;
  // Set up on the web
  configured: boolean;
  name: string;
  storeId: string;
  storeName: string;
  antennaRoles: Record<string, AntennaRole>;
  enabled: boolean;
};

const str = (v: unknown) => (typeof v === "string" ? v : "");
const num = (v: unknown, fallback = 0) => (typeof v === "number" && Number.isFinite(v) ? v : fallback);
const details = (v: unknown): Detail[] =>
  Array.isArray(v) ? v.filter((d) => d && typeof d.label === "string").map((d) => ({ label: d.label, value: String(d.value ?? "") })) : [];

/** An edge PC counts as online if it sent a heartbeat in the last 90 seconds. */
export const isOnline = (edge: Edge, now = Date.now()) => !!edge.lastSeenAt && now - edge.lastSeenAt.getTime() < 90_000;

export function subscribePasses(companyId: string, max: number, onData: (p: DoorPass[]) => void, onError: (e: Error) => void) {
  return onSnapshot(
    query(companyCol(companyId, "door_passes"), orderBy("lastSeenAt", "desc"), limit(max)),
    (snap) =>
      onData(
        snap.docs.map((d) => {
          const x = d.data();
          const u = x.unit && typeof x.unit === "object" ? (x.unit as Record<string, unknown>) : null;
          return {
            id: d.id,
            epc: str(x.epc),
            readerId: str(x.readerId),
            readerName: str(x.readerName) || str(x.readerId),
            storeName: str(x.storeName),
            direction: (["in", "out", "unknown", "passed"].includes(x.direction) ? x.direction : "passed") as Direction,
            verdict: (["alarm", "ok", "unknown_tag", "pending"].includes(x.verdict) ? x.verdict : "pending") as Verdict,
            antennas: Array.isArray(x.antennas) ? x.antennas.filter((n: unknown) => typeof n === "number") : [],
            firstSeenAt: num(x.firstSeenAt),
            lastSeenAt: num(x.lastSeenAt),
            eventCount: num(x.eventCount, 1),
            unit: u
              ? {
                  status: str(u.status), sku: str(u.sku), productTitle: str(u.productTitle), category: str(u.category),
                  brand: str(u.brand), imageUrl: str(u.imageUrl), stockedAt: str(u.stockedAt), serialNumber: str(u.serialNumber),
                  price: typeof u.price === "number" ? u.price : null, productDetails: details(u.productDetails), unitDetails: details(u.unitDetails),
                }
              : null,
          };
        }),
      ),
    onError,
  );
}

export function subscribeEdges(companyId: string, onData: (e: Edge[]) => void, onError: (e: Error) => void) {
  return onSnapshot(
    companyCol(companyId, "edges"),
    (snap) =>
      onData(
        snap.docs
          .map((d) => {
            const x = d.data();
            return {
              id: d.id,
              name: str(x.name) || str(x.hostname) || d.id,
              hostname: str(x.hostname),
              ips: Array.isArray(x.ips) ? x.ips.filter((i: unknown) => typeof i === "string") : [],
              version: str(x.version),
              lastSeenAt: toDate(x.lastSeenAt),
              scanStatus: (["requested", "scanning", "done", "error"].includes(x.scanStatus) ? x.scanStatus : "") as Edge["scanStatus"],
              scanError: str(x.scanError),
              scanFound: typeof x.scanFound === "number" ? x.scanFound : null,
              scanCompletedAt: toDate(x.scanCompletedAt),
            };
          })
          .sort((a, b) => a.name.localeCompare(b.name)),
      ),
    onError,
  );
}

export function subscribeReaders(companyId: string, onData: (r: ReaderDoc[]) => void, onError: (e: Error) => void) {
  return onSnapshot(
    companyCol(companyId, "readers"),
    (snap) =>
      onData(
        snap.docs
          .map((d) => {
            const x = d.data();
            const roles: Record<string, AntennaRole> = {};
            for (const [k, v] of Object.entries((x.antennaRoles ?? {}) as Record<string, unknown>)) {
              if (v === "in" || v === "out" || v === "off") roles[k] = v;
            }
            return {
              id: d.id,
              edgeId: str(x.edgeId),
              host: str(x.host),
              mac: str(x.mac),
              manufacturer: str(x.manufacturer),
              modelId: x.modelId == null ? "" : String(x.modelId),
              firmware: str(x.firmware),
              maxAntennas: num(x.maxAntennas),
              antennas: Array.isArray(x.antennas)
                ? x.antennas
                    .filter((a: { port?: unknown }) => typeof a?.port === "number")
                    .map((a: { port: number; connected?: unknown }) => ({ port: a.port, connected: a.connected === true }))
                    .sort((a: { port: number }, b: { port: number }) => a.port - b.port)
                : [],
              probeError: str(x.probeError),
              lastProbedAt: toDate(x.lastProbedAt),
              webConsolePort: typeof x.webConsolePort === "number" ? x.webConsolePort : null,
              connection: (["connecting", "connected", "error", "stopped"].includes(x.connection) ? x.connection : "") as ReaderDoc["connection"],
              connectionError: str(x.connectionError),
              lastReadAt: toDate(x.lastReadAt),
              configured: x.configured === true,
              name: str(x.name),
              storeId: str(x.storeId),
              storeName: str(x.storeName),
              antennaRoles: roles,
              enabled: x.enabled === true,
            };
          })
          .sort((a, b) => Number(a.configured) - Number(b.configured) || (a.name || a.host).localeCompare(b.name || b.host)),
      ),
    onError,
  );
}

const call = (data: Record<string, unknown>) => httpsCallable(functions, "readersV1")({ opId: crypto.randomUUID(), ...data });

/** Asks the edge PC to search its network, or to check just [hosts]. */
export const requestScan = (edgeId: string, hosts: string[] = []) => call({ action: "scan", edgeId, hosts });

export const configureReader = (r: {
  readerId: string; name: string; storeId: string; antennaRoles: Record<string, AntennaRole>; enabled: boolean;
}) => call({ action: "configure", ...r });

export const removeReader = (readerId: string) => call({ action: "remove", readerId });
