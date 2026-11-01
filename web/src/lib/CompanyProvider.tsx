import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { CompanyConfig } from "./config";
import type { Role } from "../auth/AuthProvider";
import {
  subscribeAlerts,
  type Alert,
  type Bin,
  type Category,
  type Locations,
  type Product,
  type Profile,
  type Rack,
  type Warehouse,
} from "./data";
import { prefetchDashboard, queries, useQuery } from "./queries";

type Lookups = {
  warehouseById: Map<string, Warehouse>;
  rackById: Map<string, Rack>;
  /** Keyed "warehouseId/binCode" — the same key inventory_sku.byLocation uses. */
  binByKey: Map<string, Bin>;
  productById: Map<string, Product>;
  productBySku: Map<string, Product>;
  categoryById: Map<string, Category>;
};

export type CompanyValue = Lookups & {
  companyId: string;
  /** The signed-in login. */
  uid: string;
  role: Role;
  isOwner: boolean;
  companyName: string;
  config: CompanyConfig;
  /** The signed-in person's own profile. */
  profile: Profile;
  locations: Locations;
  products: Product[];
  /** Business label, e.g. label("bin") → "Tray" for jewellery. */
  label: (key: string, plural?: boolean) => string;
  feature: (key: string) => boolean;
  /** "Main store / TRAY-03" for a warehouse id + bin code. */
  locationText: (warehouseId: string, binCode: string) => string;
  alerts: { list: Alert[]; unread: number; error?: Error; loading: boolean };
  reload: () => void;
};

const CompanyContext = createContext<CompanyValue | null>(null);

export function CompanyProvider({
  companyId,
  uid,
  role,
  children,
  loadingView,
  errorView,
}: {
  companyId: string;
  uid: string;
  role: Role;
  children: ReactNode;
  loadingView: ReactNode;
  errorView: (error: Error, retry: () => void) => ReactNode;
}) {
  const basics = useQuery(queries.basics(companyId, uid));

  // Start the screens' main reads now, alongside the company set-up, not after it.
  useEffect(() => prefetchDashboard(companyId), [companyId]);

  const alertsOn = basics.data?.config.features.managerAlerts ?? false;
  const [alertList, setAlertList] = useState<Alert[]>([]);
  const [alertError, setAlertError] = useState<Error>();
  const [alertsLoading, setAlertsLoading] = useState(true);

  useEffect(() => {
    if (!alertsOn) return;
    return subscribeAlerts(
      companyId,
      (list) => {
        setAlertList(list);
        setAlertError(undefined);
        setAlertsLoading(false);
      },
      (e) => {
        setAlertError(e);
        setAlertsLoading(false);
      },
    );
  }, [companyId, alertsOn]);

  const value = useMemo<CompanyValue | null>(() => {
    const b = basics.data;
    if (!b) return null;
    const warehouseById = new Map(b.locations.warehouses.map((w) => [w.id, w]));
    const rackById = new Map(b.locations.racks.map((r) => [r.id, r]));
    const binByKey = new Map(b.locations.bins.map((bin) => [`${bin.warehouseId}/${bin.binCode}`, bin]));
    const productById = new Map(b.products.map((p) => [p.id, p]));
    const productBySku = new Map(b.products.map((p) => [p.sku.trim().toLowerCase(), p]));
    const categoryById = new Map(b.categories.map((c) => [c.id, c]));
    const labels = b.config.labels;

    return {
      companyId,
      uid,
      role,
      isOwner: role === "owner",
      companyName: b.companyName || (role === "owner" ? b.profile.companyName : "") || "Your company",
      config: b.config,
      profile: b.profile,
      locations: b.locations,
      products: b.products,
      warehouseById,
      rackById,
      binByKey,
      productById,
      productBySku,
      categoryById,
      label: (key, plural = false) => {
        const l = labels[key];
        if (!l) return key;
        return plural ? l.plural : l.singular;
      },
      feature: (key) => b.config.features[key] ?? true,
      locationText: (warehouseId, binCode) => {
        if (!warehouseId && !binCode) return "";
        const w = warehouseById.get(warehouseId)?.name ?? "";
        return [w, binCode].filter(Boolean).join(" / ");
      },
      alerts: {
        list: alertsOn ? alertList : [],
        unread: alertsOn ? alertList.filter((a) => !a.read).length : 0,
        error: alertError,
        loading: alertsOn && alertsLoading,
      },
      reload: basics.reload,
    };
  }, [basics.data, basics.reload, companyId, uid, role, alertsOn, alertList, alertError, alertsLoading]);

  if (basics.error && !basics.data) return <>{errorView(basics.error, basics.reload)}</>;
  if (!value) return <>{loadingView}</>;
  return <CompanyContext.Provider value={value}>{children}</CompanyContext.Provider>;
}

export function useCompany(): CompanyValue {
  const value = useContext(CompanyContext);
  if (!value) throw new Error("useCompany must be used inside CompanyProvider");
  return value;
}
