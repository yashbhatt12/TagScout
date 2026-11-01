/**
 * Every Firestore read the screens use, each with its cache key.
 * Screens and the start-up prefetch use the same entries, so a query
 * started early is simply picked up when the screen opens.
 */
import { prefetch, useCached } from "./cache";
import {
  loadCategories,
  loadCompanyName,
  loadConfig,
  loadCycleCountMovements,
  loadLocations,
  loadMovements,
  loadProducts,
  loadProfile,
  loadSkuAggregates,
  loadStatusCounts,
} from "./data";
import { addDays, startOfDay } from "./format";

type Query<T> = { key: string; load: () => Promise<T> };

/** The last 30 days up to the end of today, shared by Overview and Movements. */
export const RECENT_DAYS = 30;

export function recentWindow(now = new Date()): { from: Date; to: Date } {
  const to = addDays(startOfDay(now), 1);
  return { from: addDays(to, -RECENT_DAYS), to };
}

export const queries = {
  /** Company set-up plus who is signed in ([uid]'s own profile; staff can't read the owner's). */
  basics: (companyId: string, uid: string) => ({
    key: `${companyId}/basics/${uid}`,
    load: async () => {
      const [config, profile, companyName, locations, products, categories] = await Promise.all([
        loadConfig(companyId),
        loadProfile(uid),
        loadCompanyName(companyId, uid),
        loadLocations(companyId),
        loadProducts(companyId),
        loadCategories(companyId),
      ]);
      return { config, profile, companyName, locations, products, categories };
    },
  }),
  statusCounts: (companyId: string) => ({
    key: `${companyId}/status-counts`,
    load: () => loadStatusCounts(companyId),
  }),
  skuAggregates: (companyId: string) => ({
    key: `${companyId}/stock`,
    load: () => loadSkuAggregates(companyId),
  }),
  movements: (companyId: string, from: Date, to: Date) => ({
    key: `${companyId}/movements/${from.getTime()}-${to.getTime()}`,
    load: () => loadMovements(companyId, from, to),
  }),
  cycleCounts: (companyId: string) => ({
    key: `${companyId}/cycle-counts`,
    load: () => loadCycleCountMovements(companyId),
  }),
} satisfies Record<string, (...args: never[]) => Query<unknown>>;

export function useQuery<T>(query: Query<T>) {
  return useCached(query.key, query.load);
}

/** Starts every screen's main query at sign-in, in parallel with the company set-up. */
export function prefetchDashboard(companyId: string) {
  const { from, to } = recentWindow();
  const list: Query<unknown>[] = [
    queries.statusCounts(companyId),
    queries.skuAggregates(companyId),
    queries.movements(companyId, from, to),
    queries.cycleCounts(companyId),
  ];
  list.forEach((query) => prefetch(query.key, query.load));
}
