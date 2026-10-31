/**
 * Small in-memory cache shared by every screen.
 *
 * - Leaving a screen keeps its data; coming back shows it instantly.
 * - Data older than FRESH_MS is shown straight away and refreshed in the background.
 * - Two screens asking for the same thing share one request.
 * - Refresh buttons force a new read; signing out clears everything.
 */
import { useCallback, useEffect, useSyncExternalStore } from "react";

export const FRESH_MS = 5 * 60_000;

type Snapshot = { data?: unknown; error?: Error; at: number; loading: boolean };

const EMPTY: Snapshot = { at: 0, loading: false };
const snapshots = new Map<string, Snapshot>();
const inflight = new Map<string, Promise<unknown>>();
const subscribers = new Map<string, Set<() => void>>();
// Bumped by clearCache() so answers for a previous login are dropped.
let generation = 0;

function toError(e: unknown): Error {
  return e instanceof Error ? e : new Error(String(e));
}

function update(key: string, patch: Partial<Snapshot>) {
  snapshots.set(key, { ...(snapshots.get(key) ?? EMPTY), ...patch });
  subscribers.get(key)?.forEach((notify) => notify());
}

export function fetchCached<T>(
  key: string,
  load: () => Promise<T>,
  { force = false, maxAgeMs = FRESH_MS }: { force?: boolean; maxAgeMs?: number } = {},
): Promise<T> {
  const running = inflight.get(key);
  if (running) return running as Promise<T>;

  const current = snapshots.get(key);
  if (!force && current?.data !== undefined && Date.now() - current.at < maxAgeMs) {
    return Promise.resolve(current.data as T);
  }

  const gen = generation;
  const started = performance.now();
  update(key, { loading: true });

  const request = load().then(
    (data) => {
      if (gen === generation) {
        inflight.delete(key);
        update(key, { data, error: undefined, at: Date.now(), loading: false });
        console.debug(`[TagScout] ${key.split("/").slice(1).join("/")} loaded in ${Math.round(performance.now() - started)} ms`);
      }
      return data;
    },
    (e: unknown) => {
      const error = toError(e);
      if (gen === generation) {
        inflight.delete(key);
        update(key, { error, loading: false });
      }
      throw error;
    },
  );
  inflight.set(key, request);
  return request;
}

/** Starts a load in the background without waiting for it. */
export function prefetch<T>(key: string, load: () => Promise<T>) {
  fetchCached(key, load).catch(() => undefined);
}

export function clearCache() {
  generation += 1;
  inflight.clear();
  const keys = [...snapshots.keys()];
  snapshots.clear();
  keys.forEach((key) => subscribers.get(key)?.forEach((notify) => notify()));
}

export type CachedState<T> = {
  data: T | undefined;
  error: Error | undefined;
  /** True while a request is running, including a background refresh. */
  loading: boolean;
  /** When the data on screen was read from Firestore. */
  updatedAt: Date | undefined;
  reload: () => void;
};

/**
 * Reads [key] through the cache. The key must describe the whole query
 * (company, range and so on): the same key always means the same data.
 */
export function useCached<T>(key: string, load: () => Promise<T>, maxAgeMs = FRESH_MS): CachedState<T> {
  const subscribe = useCallback(
    (notify: () => void) => {
      let set = subscribers.get(key);
      if (!set) {
        set = new Set();
        subscribers.set(key, set);
      }
      set.add(notify);
      return () => {
        set.delete(notify);
      };
    },
    [key],
  );
  const snapshot = useSyncExternalStore(subscribe, () => snapshots.get(key) ?? EMPTY);

  useEffect(() => {
    fetchCached(key, load, { maxAgeMs }).catch(() => undefined);
    // The key fully describes the query, so a new load function for the same key changes nothing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, maxAgeMs]);

  const reload = useCallback(() => {
    fetchCached(key, load, { force: true }).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return {
    data: snapshot.data as T | undefined,
    error: snapshot.error,
    loading: snapshot.loading,
    updatedAt: snapshot.at ? new Date(snapshot.at) : undefined,
    reload,
  };
}
