import type { StoryMapMarkerData } from "./mapTypes";

export default function createMapMarkerLoader() {
  const controller = new AbortController();
  const cache = new Map<string, StoryMapMarkerData | null>();
  const pending = new Map<string, Promise<void>>();

  return {
    cache,
    abort: () => controller.abort(),
    async load(ids: string[]) {
      const unique = [...new Set(ids)];
      const missing = unique.filter((id) => !cache.has(id) && !pending.has(id));
      for (let offset = 0; offset < missing.length; offset += 100) {
        const batch = missing.slice(offset, offset + 100);
        const params = new URLSearchParams({ ids: batch.join(",") });
        const request = fetch(`/api/stories/map-markers?${params}`, { signal: controller.signal })
          .then((response) => {
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            return response.json() as Promise<StoryMapMarkerData[]>;
          })
          .then((markers) => {
            if (controller.signal.aborted) return;
            batch.forEach((id) => cache.set(id, null));
            markers.forEach((marker) => { if (batch.includes(marker.id)) cache.set(marker.id, marker); });
          })
          .finally(() => batch.forEach((id) => pending.delete(id)));
        batch.forEach((id) => pending.set(id, request));
      }
      await Promise.all([...new Set(unique.map((id) => pending.get(id)).filter((request) => request !== undefined))]);
      return cache;
    },
  };
}
