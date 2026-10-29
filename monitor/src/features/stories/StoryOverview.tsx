import { useCallback, useMemo, useRef, useState } from "react";
import { Alert, Box, ButtonBase, Divider, Paper, Stack, Typography } from "@mui/material";
import StateMessage from "../../shared/StateMessage";
import LiveMap, { type MapBounds } from "../live-map/LiveMap";
import StoryDetails from "./StoryDetails";
import StoryFilters, { type StoryFilterValues } from "./StoryFilters";
import StoryRefreshStatus from "./StoryRefreshStatus";
import { FormattedDate } from "./StoryMetadata";
import { readLagebildUrl } from "./lagebildUrlState";
import type { Story } from "./storyTypes";
import useLagebildUrlSync from "./useLagebildUrlSync";
import useStories from "./useStories";

function isInBounds(story: Story, bounds: MapBounds) {
  if (!story.location) return false;
  const { latitude, longitude } = story.location;
  const longitudeVisible = bounds.west <= bounds.east
    ? longitude >= bounds.west && longitude <= bounds.east
    : longitude >= bounds.west || longitude <= bounds.east;
  return latitude >= bounds.south && latitude <= bounds.north && longitudeVisible;
}

export default function StoryOverview() {
  const [initialUrlState] = useState(() => readLagebildUrl(window.location.search));
  const [filters, setFilters] = useState(initialUrlState.filters);
  const [selectedId, setSelectedId] = useState<string | undefined>(initialUrlState.selectedId);
  const [mapBounds, setMapBounds] = useState<MapBounds | undefined>(initialUrlState.mapBounds);
  const hasReportedBounds = useRef(false);
  const { page, regions, error, isLoading, lastLoadedAt, refresh } = useStories(filters);

  useLagebildUrlSync({ filters, mapBounds, selectedId });

  const filteredStories = useMemo(() => {
    const term = filters.query.trim().toLocaleLowerCase("de");
    if (!term) return page?.stories ?? [];
    return (page?.stories ?? []).filter((story) => [story.representativeArticle.title, story.representativeArticle.domain, story.location?.name, story.location?.countryCode, story.location?.regionCode]
      .some((value) => value?.toLocaleLowerCase("de").includes(term)));
  }, [filters.query, page]);
  const stories = useMemo(
    () => mapBounds ? filteredStories.filter((story) => isInBounds(story, mapBounds)) : filteredStories,
    [filteredStories, mapBounds],
  );

  const select = useCallback((id: string) => setSelectedId(id), []);
  const changeFilters = (next: StoryFilterValues) => { setSelectedId(undefined); setFilters(next); };
  const changeBounds = useCallback((bounds: MapBounds) => {
    if (hasReportedBounds.current) setSelectedId(undefined);
    hasReportedBounds.current = true;
    setMapBounds(bounds);
  }, []);

  return (
    <Box component="main" sx={{ display: "flex", flexDirection: "column", flex: 1, minHeight: 0, px: 4, pt: 2, pb: 3, overflow: "hidden" }}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", mb: 1.5, flexShrink: 0 }}>
        <Box sx={{ minWidth: 150 }}><Typography variant="overline" color="primary.main">Weltweit</Typography><Typography variant="h3">Globales Lagebild</Typography></Box>
        <Box sx={{ flex: 1 }}><StoryFilters filters={filters} regions={regions} onChange={changeFilters} /></Box>
      </Stack>

      <Paper component="section" aria-label="Globales Lagebild" variant="outlined" sx={{ display: "grid", gridTemplateColumns: "minmax(560px, 1fr) minmax(300px, 360px)", flex: 1, minHeight: 0, overflow: "hidden", borderRadius: 2.5, boxShadow: "0 16px 40px rgba(20,45,34,.09)" }}>
        <Box component="section" aria-label="Weltkarte" sx={{ position: "relative", minHeight: 0, bgcolor: "#dbeae3" }}>
          {!page && isLoading ? <StateMessage>Ereignisse werden geladen …</StateMessage> : <LiveMap stories={filteredStories} selectedId={selectedId} initialBounds={initialUrlState.mapBounds} onSelect={select} onBoundsChange={changeBounds} />}
        </Box>
        <Box component="aside" aria-label="Ereignisse und Details" sx={{ display: "flex", flexDirection: "column", minHeight: 0, borderLeft: 1, borderColor: "divider", overflow: "hidden" }}>
          {selectedId ? (
            <Box sx={{ height: "100%", overflow: "auto", p: 2.5 }}><StoryDetails id={selectedId} onBack={() => setSelectedId(undefined)} /></Box>
          ) : (
            <>
              <Box sx={{ px: 2.5, py: 2, flexShrink: 0 }}>
                <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "baseline" }}><Typography variant="h3">Priorisierte Ereignisse</Typography><Typography color="text.secondary" sx={{ fontSize: "0.75rem" }}>{stories.length} von {page?.total ?? 0}</Typography></Stack>
                <StoryRefreshStatus isLoading={isLoading} lastLoadedAt={lastLoadedAt} onRefresh={refresh} />
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
