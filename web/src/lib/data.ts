/**
 * Firestore reads for the dashboard. Read-only: nothing here writes.
 *
 * Every query is chosen so it needs no composite index:
 *   movements by date  → range + orderBy on the same field (timestamp)
 *   cycle counts       → equality on referenceType, sorted in the browser
 *   status counts      → equality on status, count() aggregation
 */
import {
  Timestamp,
  getCountFromServer,
  getDoc,
  getDocs,
  limit,
  onSnapshot,
  orderBy,
  query,
  where,
  doc,
  type DocumentData,
} from "firebase/firestore";
import { companyCol, companyDoc, db } from "../firebase";
import { parseConfig, type CompanyConfig, UNIT_STATUSES } from "./config";
import { toDate } from "./format";

// ── Types ─────────────────────────────────────────────────────────

export type Warehouse = { id: string; name: string; address: string };
export type Rack = { id: string; name: string; warehouseId: string };
export type Bin = { id: string; binCode: string; name: string; rackId: string; warehouseId: string };
export type Locations = { warehouses: Warehouse[]; racks: Rack[]; bins: Bin[] };

export type Product = {
  id: string;
  sku: string;
  title: string;
  productType: string;
  categoryId: string;
  imageUrl: string;
  status: string;
  isActive: boolean;
};

export type Category = { id: string; name: string; productType: string; parentId: string };

export type SkuAggregate = {
  productId: string;
  sku: string;
  totalQuantity: number;
  /** Keyed "warehouseId/binCode" (see updateSkuAggregate). */
  byLocation: Record<string, number>;
  lastComputedAt: Date | null;
};

export type Movement = {
  id: string;
  type: string;
  productId: string;
  sku: string;
  epc: string;
  quantity: number;
  fromWarehouseId: string;
  fromBinCode: string;
  toWarehouseId: string;
  toBinCode: string;
  referenceType: string;
  referenceId: string;
  userId: string;
  notes: string;
  batchId: string;
  timestamp: Date | null;
};

export type Alert = {
  id: string;
  epc: string;
  itemCode: string;
  category: string;
  price: number | null;
  readerId: string;
  antennaPort: number | null;
  rssi: number | null;
  severity: string;
  read: boolean;
  createdAt: Date | null;
};

export type Profile = { name: string; email: string; companyName: string };

// ── Helpers ───────────────────────────────────────────────────────

const str = (v: unknown) => (typeof v === "string" ? v : "");
const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : 0);
const numOrNull = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : null);

// ── Company basics ────────────────────────────────────────────────

export async function loadConfig(companyId: string): Promise<CompanyConfig> {
  const snap = await getDoc(companyDoc(companyId, "settings", "config"));
  return parseConfig(snap.exists() ? (snap.data() as Record<string, unknown>) : undefined);
}

export async function loadProfile(uid: string): Promise<Profile> {
  const snap = await getDoc(doc(db, "users", uid));
  const d = snap.data() ?? {};
  return { name: str(d.name), email: str(d.email), companyName: str(d.companyName) };
}

/** Warehouses → racks → bins (subcollections, read the same way the app does). */
export async function loadLocations(companyId: string): Promise<Locations> {
  const warehouseSnap = await getDocs(companyCol(companyId, "warehouses"));
  const warehouses: Warehouse[] = warehouseSnap.docs.map((d) => ({
    id: d.id,
    name: str(d.get("name")) || "Unnamed",
    address: str(d.get("address")),
  }));

  const racks: Rack[] = [];
  const bins: Bin[] = [];

  await Promise.all(
    warehouses.map(async (w) => {
      const rackSnap = await getDocs(companyCol(companyId, "warehouses", w.id, "racks"));
      await Promise.all(
        rackSnap.docs.map(async (r) => {
          racks.push({ id: r.id, name: str(r.get("name")) || "Unnamed", warehouseId: w.id });
          const binSnap = await getDocs(companyCol(companyId, "warehouses", w.id, "racks", r.id, "bins"));
          binSnap.docs.forEach((b) =>
            bins.push({
              id: b.id,
              binCode: str(b.get("binCode")),
              name: str(b.get("name")),
              rackId: r.id,
              warehouseId: w.id,
            }),
          );
        }),
      );
    }),
  );

  const byName = <T extends { name: string }>(a: T, b: T) => a.name.localeCompare(b.name, undefined, { numeric: true });
  warehouses.sort(byName);
  racks.sort(byName);
  bins.sort((a, b) => a.binCode.localeCompare(b.binCode, undefined, { numeric: true }));
  return { warehouses, racks, bins };
}

export async function loadProducts(companyId: string): Promise<Product[]> {
  const snap = await getDocs(companyCol(companyId, "products"));
  return snap.docs.map((d) => {
    const x = d.data();
    return {
      id: d.id,
      sku: str(x.sku),
      title: str(x.title) || str(x.name) || str(x.sku),
      productType: str(x.productType) || "general",
      categoryId: str(x.categoryId),
      imageUrl: str(x.imageUrl),
      status: str(x.status) || "active",
      isActive: x.isActive !== false,
    };
  });
}

export async function loadCategories(companyId: string): Promise<Category[]> {
  const snap = await getDocs(companyCol(companyId, "categories"));
  return snap.docs.map((d) => ({
    id: d.id,
    name: str(d.get("name")),
    productType: str(d.get("productType")),
    parentId: str(d.get("parentId")),
  }));
}

// ── Stock ─────────────────────────────────────────────────────────

export async function loadSkuAggregates(companyId: string): Promise<SkuAggregate[]> {
  const snap = await getDocs(companyCol(companyId, "inventory_sku"));
  return snap.docs.map((d) => {
    const x = d.data();
    const byLocation: Record<string, number> = {};
    if (x.byLocation && typeof x.byLocation === "object") {
      for (const [key, value] of Object.entries(x.byLocation as Record<string, unknown>)) {
        const n = num(value);
        if (n > 0) byLocation[key] = n;
      }
    }
    return {
      productId: str(x.productId) || d.id,
      sku: str(x.sku),
      totalQuantity: num(x.totalQuantity),
      byLocation,
      lastComputedAt: toDate(x.lastComputedAt),
    };
  });
}

/** Unit count per status, plus the total. One count() per status. */
export async function loadStatusCounts(companyId: string): Promise<{ total: number; byStatus: Record<string, number> }> {
  const units = companyCol(companyId, "inventory_units");
  const [total, ...counts] = await Promise.all([
    getCountFromServer(units).then((s) => s.data().count),
    ...UNIT_STATUSES.map((s) =>
      getCountFromServer(query(units, where("status", "==", s.key))).then((r) => r.data().count),
    ),
  ]);
  const byStatus: Record<string, number> = {};
  UNIT_STATUSES.forEach((s, i) => {
    byStatus[s.key] = counts[i];
  });
  return { total, byStatus };
}

// ── Movements ─────────────────────────────────────────────────────

function mapMovement(id: string, x: DocumentData): Movement {
  return {
    id,
    type: str(x.type),
    productId: str(x.productId),
    sku: str(x.sku),
    epc: str(x.epc),
    quantity: num(x.quantity),
    fromWarehouseId: str(x.fromWarehouseId),
    fromBinCode: str(x.fromBinCode),
    toWarehouseId: str(x.toWarehouseId),
    toBinCode: str(x.toBinCode),
    referenceType: str(x.referenceType),
    referenceId: str(x.referenceId),
    userId: str(x.userId),
    notes: str(x.notes),
    batchId: str(x.batchId),
    timestamp: toDate(x.timestamp),
  };
}

export const MOVEMENT_LIMIT = 5000;

/** Newest first, between [from] (inclusive) and [to] (exclusive). */
export async function loadMovements(
  companyId: string,
  from: Date,
  to: Date,
  max = MOVEMENT_LIMIT,
): Promise<{ rows: Movement[]; capped: boolean }> {
  const q = query(
    companyCol(companyId, "movements"),
    where("timestamp", ">=", Timestamp.fromDate(from)),
    where("timestamp", "<", Timestamp.fromDate(to)),
    orderBy("timestamp", "desc"),
    limit(max),
  );
  const snap = await getDocs(q);
  return { rows: snap.docs.map((d) => mapMovement(d.id, d.data())), capped: snap.size >= max };
}

/** Every cycle-count discrepancy the app has logged, newest first. */
export async function loadCycleCountMovements(companyId: string): Promise<Movement[]> {
  const q = query(companyCol(companyId, "movements"), where("referenceType", "==", "cyclecount"), limit(MOVEMENT_LIMIT));
  const snap = await getDocs(q);
  return snap.docs
    .map((d) => mapMovement(d.id, d.data()))
    .sort((a, b) => (b.timestamp?.getTime() ?? 0) - (a.timestamp?.getTime() ?? 0));
}

// ── Alerts (realtime) ─────────────────────────────────────────────

export function subscribeAlerts(
  companyId: string,
  onData: (alerts: Alert[]) => void,
  onError: (e: Error) => void,
): () => void {
  const q = query(companyCol(companyId, "alerts"), orderBy("createdAt", "desc"), limit(200));
  return onSnapshot(
    q,
    (snap) =>
      onData(
        snap.docs.map((d) => {
          const x = d.data();
          return {
            id: d.id,
            epc: str(x.epc),
            itemCode: str(x.itemCode),
            category: str(x.category),
            price: numOrNull(x.price),
            readerId: str(x.readerId),
            antennaPort: numOrNull(x.antennaPort),
            rssi: numOrNull(x.rssi),
            severity: str(x.severity) || "high",
            read: x.read === true,
            createdAt: toDate(x.createdAt) ?? toDate(x.firstSeenAt),
          };
        }),
      ),
    onError,
  );
}
