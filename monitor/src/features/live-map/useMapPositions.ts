import { useEffect, useState } from "react";
import type { StoryFilterValues } from "../stories/StoryFilters";
import type { StoryMapPosition } from "./mapTypes";

const EMPTY_POSITIONS: StoryMapPosition[] = [];

export default function useMapPositions(filters: StoryFilterValues, refreshKey: number) {
  const query = filters.query.trim();
  const country = filters.country.trim();
  const scope = JSON.stringify([filters.hours, query, country]);
  const [debouncedQuery, setDebouncedQuery] = useState(query);
  const [snapshot, setSnapshot] = useState<{ scope: string; positions: StoryMapPosition[] }>();
  const [failure, setFailure] = useState<{ scope: string; message: string }>();
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const timeout = window.setTimeout(() => setDebouncedQuery(query), 300);
    return () => window.clearTimeout(timeout);
  }, [query]);

  useEffect(() => {
    setFailure(undefined);
    setIsLoading(true);
    if (debouncedQuery !== query) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ from: new Date(Date.now() - Number(filters.hours) * 3_600_000).toISOString() });
    if (query) params.set("q", query);
    if (country) params.set("country", country);
    fetch(`/api/stories/map-positions?${params}`, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<StoryMapPosition[]>;
      })
      .then((positions) => { if (!controller.signal.aborted) setSnapshot({ scope, positions }); })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setFailure({ scope, message: reason instanceof Error ? reason.message : "Unbekannter Fehler" });
      })
      .finally(() => { if (!controller.signal.aborted) setIsLoading(false); });
    return () => controller.abort();
  }, [country, debouncedQuery, filters.hours, query, refreshKey, scope]);

  return {
    positions: snapshot?.scope === scope ? snapshot.positions : EMPTY_POSITIONS,
    error: failure?.scope === scope ? failure.message : undefined,
    isLoading,
  };
}
