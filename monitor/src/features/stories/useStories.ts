import { useEffect, useState } from "react";
import type { StoryPage } from "./storyTypes";
import type { RegionOption, StoryFilterValues } from "./StoryFilters";

const PAGE_SIZE = 100;

export default function useStories(filters: StoryFilterValues) {
  const [page, setPage] = useState<StoryPage>();
  const [error, setError] = useState<string>();
  const [isLoading, setIsLoading] = useState(false);
  const [lastLoadedAt, setLastLoadedAt] = useState<Date>();
  const [regions, setRegions] = useState<RegionOption[]>([]);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const from = new Date(Date.now() - Number(filters.hours) * 60 * 60 * 1000).toISOString();
    const query = new URLSearchParams({ offset: "0", limit: String(PAGE_SIZE), sort: "articleCount", direction: "desc", from });
    if (filters.region.trim()) query.set("region", filters.region.trim());

    setIsLoading(true);
    setError(undefined);
    fetch(`/api/stories?${query}`, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<StoryPage>;
      })
      .then((nextPage) => {
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
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "Unbekannter Fehler");
      })
      .finally(() => { if (!controller.signal.aborted) setIsLoading(false); });

    return () => controller.abort();
  }, [filters.hours, filters.region, refreshKey]);

  return { page, regions, error, isLoading, lastLoadedAt, refresh: () => setRefreshKey((key) => key + 1) };
}
