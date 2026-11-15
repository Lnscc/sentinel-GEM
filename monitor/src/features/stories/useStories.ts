import { useEffect, useRef, useState } from "react";
import type { MapBounds } from "../live-map/LiveMap";
import type { StoryPage } from "./storyTypes";
import type { RegionOption, StoryFilterValues } from "./StoryFilters";

const PAGE_SIZE = 100;
const REFRESH_INTERVAL_MS = 60_000;
const SEARCH_DEBOUNCE_MS = 300;

export default function useStories(filters: StoryFilterValues, mapBounds?: MapBounds) {
  const queryValue = filters.query.trim();
  const bounds = mapBounds && [mapBounds.west, mapBounds.south, mapBounds.east, mapBounds.north]
    .map((value) => Number(value.toFixed(5))).join(",");
  const visibleScope = JSON.stringify([filters.hours, queryValue, filters.region.trim(), bounds]);
  const [page, setPage] = useState<StoryPage>();
  const [debouncedQuery, setDebouncedQuery] = useState(queryValue);
  const [error, setError] = useState<string>();
  const [isLoading, setIsLoading] = useState(false);
  const [lastLoadedAt, setLastLoadedAt] = useState<Date>();
  const [regions, setRegions] = useState<RegionOption[]>([]);
  const [refreshKey, setRefreshKey] = useState(0);
  const pageScope = useRef<string | undefined>(undefined);
  const pageCache = useRef<{ scope: string; page: StoryPage } | undefined>(undefined);

  useEffect(() => {
    const interval = window.setInterval(() => setRefreshKey((key) => key + 1), REFRESH_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, []);

  useEffect(() => {
    if (queryValue === debouncedQuery) return;
    const timeout = window.setTimeout(() => setDebouncedQuery(queryValue), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timeout);
  }, [debouncedQuery, queryValue]);

  useEffect(() => {
    setError(undefined);
    setPage(pageScope.current === visibleScope ? pageCache.current?.page : undefined);
  }, [visibleScope]);

  useEffect(() => {
    if (debouncedQuery !== queryValue) {
      setIsLoading(true);
      return;
    }
    const controller = new AbortController();
    const from = new Date(Date.now() - Number(filters.hours) * 60 * 60 * 1000).toISOString();
    const query = new URLSearchParams({ offset: "0", limit: String(PAGE_SIZE), sort: "priority", direction: "desc", from });
    if (debouncedQuery) query.set("q", debouncedQuery);
    if (filters.region.trim()) query.set("region", filters.region.trim());
    if (bounds) query.set("bounds", bounds);
    const scope = JSON.stringify([filters.hours, debouncedQuery, filters.region.trim(), bounds]);

    setIsLoading(true);
    setError(undefined);
    fetch(`/api/stories?${query}`, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<StoryPage>;
      })
      .then((nextPage) => {
        if (controller.signal.aborted) return;
        pageScope.current = scope;
        pageCache.current = { scope, page: nextPage };
        setPage(nextPage);
        setRegions((current) => {
          const known = new Map(current.map((region) => [region.code, region]));
          nextPage.stories.forEach(({ location }) => {
            if (location?.regionCode && location.name && !known.has(location.regionCode)) {
              known.set(location.regionCode, { code: location.regionCode, name: location.name });
            }
          });
          return [...known.values()].sort((left, right) => left.name.localeCompare(right.name, "de"));
        });
        setLastLoadedAt(new Date());
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          if (pageScope.current !== scope) setPage(undefined);
          setError(reason instanceof Error ? reason.message : "Unbekannter Fehler");
        }
      })
      .finally(() => { if (!controller.signal.aborted) setIsLoading(false); });

    return () => controller.abort();
  }, [bounds, debouncedQuery, filters.hours, filters.region, queryValue, refreshKey]);

  return { page, regions, error, isLoading, lastLoadedAt, refresh: () => setRefreshKey((key) => key + 1) };
}
