import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Box, ButtonBase, Divider, Paper, Stack, Typography } from "@mui/material";
import StateMessage from "../../shared/StateMessage";
import LiveMap, { type MapBounds } from "../live-map/LiveMap";
import StoryDetails from "./StoryDetails";
import StoryFilters, { DEFAULT_FILTERS, type StoryFilterValues } from "./StoryFilters";
import StoryRefreshStatus from "./StoryRefreshStatus";
import { FormattedDate } from "./StoryMetadata";
import type { StoryPage } from "./storyTypes";

const PAGE_SIZE = 100;

export default function StoryOverview() {
  const [page, setPage] = useState<StoryPage>();
  const [error, setError] = useState<string>();
  const [isLoading, setIsLoading] = useState(false);
  const [lastLoadedAt, setLastLoadedAt] = useState<Date>();
  const [refreshKey, setRefreshKey] = useState(0);
  const [filters, setFilters] = useState<StoryFilterValues>(DEFAULT_FILTERS);
  const [selectedId, setSelectedId] = useState<string>();
  const [mapBounds, setMapBounds] = useState<MapBounds>();

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
      .then((nextPage) => { setPage(nextPage); setLastLoadedAt(new Date()); })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "Unbekannter Fehler");
      })
      .finally(() => { if (!controller.signal.aborted) setIsLoading(false); });
    return () => controller.abort();
  }, [filters.hours, filters.region, refreshKey]);

  const filteredStories = useMemo(() => {
    const term = filters.query.trim().toLocaleLowerCase("de");
    if (!term) return page?.stories ?? [];
    return (page?.stories ?? []).filter((story) => [story.representativeArticle.title, story.representativeArticle.domain, story.location?.name, story.location?.countryCode, story.location?.regionCode]
      .some((value) => value?.toLocaleLowerCase("de").includes(term)));
  }, [filters.query, page]);

  const stories = useMemo(() => filteredStories.filter((story) => {
    if (!mapBounds || !story.location) return !mapBounds;
    const { latitude, longitude } = story.location;
    const longitudeVisible = mapBounds.west <= mapBounds.east
      ? longitude >= mapBounds.west && longitude <= mapBounds.east
      : longitude >= mapBounds.west || longitude <= mapBounds.east;
    return latitude >= mapBounds.south && latitude <= mapBounds.north && longitudeVisible;
  }), [filteredStories, mapBounds]);

  useEffect(() => {
    if (selectedId && !stories.some((story) => story.id === selectedId)) setSelectedId(undefined);
  }, [selectedId, stories]);

  const select = useCallback((id: string) => setSelectedId(id), []);
  const changeFilters = (next: StoryFilterValues) => { setSelectedId(undefined); setFilters(next); };

  return (
    <Box component="main" sx={{ display: "flex", flexDirection: "column", flex: 1, minHeight: 0, px: 4, pt: 2, pb: 3, overflow: "hidden" }}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", mb: 1.5, flexShrink: 0 }}>
        <Box sx={{ minWidth: 150 }}><Typography variant="overline" color="primary.main">Weltweit</Typography><Typography variant="h3">Globales Lagebild</Typography></Box>
        <Box sx={{ flex: 1 }}><StoryFilters filters={filters} onChange={changeFilters} /></Box>
      </Stack>

      <Paper component="section" aria-label="Globales Lagebild" variant="outlined" sx={{ display: "grid", gridTemplateColumns: "minmax(560px, 1fr) minmax(300px, 360px)", flex: 1, minHeight: 0, overflow: "hidden", borderRadius: 2.5, boxShadow: "0 16px 40px rgba(20,45,34,.09)" }}>
        <Box component="section" aria-label="Weltkarte" sx={{ position: "relative", minHeight: 0, bgcolor: "#dbeae3" }}>
          {!page && isLoading ? <StateMessage>Ereignisse werden geladen …</StateMessage> : <LiveMap stories={filteredStories} selectedId={selectedId} onSelect={select} onBoundsChange={setMapBounds} />}
        </Box>
        <Box component="aside" aria-label="Ereignisse und Details" sx={{ display: "flex", flexDirection: "column", minHeight: 0, borderLeft: 1, borderColor: "divider", overflow: "hidden" }}>
          {selectedId ? (
            <Box sx={{ height: "100%", overflow: "auto", p: 2.5 }}><StoryDetails id={selectedId} onBack={() => setSelectedId(undefined)} /></Box>
          ) : (
            <>
              <Box sx={{ px: 2.5, py: 2, flexShrink: 0 }}>
                <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "baseline" }}><Typography variant="h3">Priorisierte Ereignisse</Typography><Typography color="text.secondary" sx={{ fontSize: "0.75rem" }}>{stories.length} von {page?.total ?? 0}</Typography></Stack>
                {lastLoadedAt && <StoryRefreshStatus isLoading={isLoading} lastLoadedAt={lastLoadedAt} onRefresh={() => setRefreshKey((key) => key + 1)} />}
              </Box>
              <Divider />
              {error && <Alert severity="error" sx={{ m: 2 }}>Die Ereignisse konnten nicht geladen werden. {error}</Alert>}
              {page && stories.length === 0 && <StateMessage>Kein Ereignis passt zu diesen Filtern.</StateMessage>}
              <Box role="list" aria-label="Gefilterte Ereignisse" sx={{ flex: 1, minHeight: 0, overflow: "auto" }}>
                {stories.map((story, index) => (
                  <ButtonBase component="article" role="listitem" key={story.id} onClick={() => select(story.id)} sx={{ display: "grid", gridTemplateColumns: "36px 1fr", gap: 1.5, width: "100%", p: 2.25, textAlign: "left", alignItems: "start", borderBottom: 1, borderColor: "divider", "&:hover": { bgcolor: "#f8faf9" } }}>
                    <Box sx={{ display: "grid", placeItems: "center", width: 34, height: 34, borderRadius: 1, bgcolor: "#eaf1ed", color: "primary.dark", fontSize: "0.75rem", fontWeight: 800 }}>{index + 1}</Box>
                    <Box sx={{ minWidth: 0 }}>
                      <Typography color="primary.dark" sx={{ fontSize: "0.75rem", fontWeight: 700 }}>{story.location?.name ?? "Ohne Ortsangabe"}</Typography>
                      <Typography variant="h3" sx={{ my: 0.75, fontSize: "1.05rem" }}>{story.representativeArticle.title}</Typography>
                      <Typography color="text.secondary" sx={{ fontSize: "0.75rem" }}><FormattedDate value={story.effectiveTo} /> · {story.memberCount} Artikel · {story.representativeArticle.domain}</Typography>
                    </Box>
                  </ButtonBase>
                ))}
              </Box>
            </>
          )}
        </Box>
      </Paper>
    </Box>
  );
}
