import { useCallback, useState } from "react";
import { Alert, Box, ButtonBase, Divider, Paper, Stack, Typography } from "@mui/material";
import StateMessage from "../../shared/StateMessage";
import LiveMap, { type MapBounds } from "../live-map/LiveMap";
import useMapPositions from "../live-map/useMapPositions";
import StoryDetails from "./StoryDetails";
import StoryFilters, { DEFAULT_FILTERS, type StoryFilterValues } from "./StoryFilters";
import StoryImage from "./StoryImage";
import StoryRefreshStatus from "./StoryRefreshStatus";
import { FormattedDate } from "./StoryMetadata";
import { readLagebildUrl } from "./lagebildUrlState";
import useLagebildUrlSync from "./useLagebildUrlSync";
import useStories from "./useStories";

export default function StoryOverview() {
  const [initialUrlState] = useState(() => readLagebildUrl(window.location.search));
  const [filters, setFilters] = useState(initialUrlState.filters);
  const [selectedId, setSelectedId] = useState<string | undefined>(initialUrlState.selectedId);
  const [mapBounds, setMapBounds] = useState<MapBounds | undefined>(initialUrlState.mapBounds);
  const [worldViewReset, setWorldViewReset] = useState(0);
  const { page, countries, countriesError, error, isLoading, lastLoadedAt, refreshKey, refresh } = useStories(filters, mapBounds);
  const mapData = useMapPositions(filters, refreshKey);

  useLagebildUrlSync({ filters, mapBounds, selectedId });

  const stories = page?.stories ?? [];

  const select = useCallback((id: string) => setSelectedId(id), []);
  const changeFilters = (next: StoryFilterValues) => { setSelectedId(undefined); setFilters(next); };
  const changeBounds = useCallback((bounds: MapBounds) => {
    setSelectedId(undefined);
    setMapBounds(bounds);
  }, []);
  const worldView = () => {
    setSelectedId(undefined);
    setMapBounds(undefined);
    setWorldViewReset((value) => value + 1);
  };
  const resetFilters = () => { changeFilters(DEFAULT_FILTERS); worldView(); };

  return (
    <Box component="main" sx={{ display: "flex", flexDirection: "column", flex: 1, minWidth: 0, minHeight: 0, px: { xs: 2, lg: 4 }, pt: 2, pb: 3, overflow: "hidden" }}>
      <Stack direction={{ xs: "column", lg: "row" }} spacing={2} sx={{ alignItems: { xs: "stretch", lg: "center" }, mb: 1.5, flexShrink: 0 }}>
        <Box sx={{ minWidth: 150 }}><Typography variant="overline" color="primary.main">Weltweit</Typography><Typography variant="h3">Globales Lagebild</Typography></Box>
        <Box sx={{ flex: 1, minWidth: 0 }}><StoryFilters filters={filters} countries={countries} error={countriesError} onChange={changeFilters} onReset={resetFilters} mapFilterActive={Boolean(mapBounds)} onWorldView={worldView} /></Box>
      </Stack>

      <Paper component="section" aria-label="Globales Lagebild" variant="outlined" sx={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr) minmax(300px, 360px)", flex: 1, minHeight: 0, overflow: "hidden", borderRadius: 2.5, boxShadow: "0 16px 40px rgba(20,45,34,.09)" }}>
        <Box component="section" aria-label="Weltkarte" sx={{ position: "relative", minWidth: 0, minHeight: 0, bgcolor: "#dbeae3" }}>
          <LiveMap positions={mapData.positions} selectedId={selectedId} initialBounds={initialUrlState.mapBounds} worldViewReset={worldViewReset} onSelect={select} onBoundsChange={changeBounds} />
          {mapData.isLoading && mapData.positions.length === 0 && <Box sx={{ position: "absolute", top: 16, left: 16, zIndex: 2, bgcolor: "background.paper", borderRadius: 1, px: 2, py: 1 }} role="status">Kartenereignisse werden geladen …</Box>}
          {mapData.error && <Alert severity="error" sx={{ position: "absolute", top: 16, left: 16, right: 56, zIndex: 2 }}>Die Kartenereignisse konnten nicht geladen werden. {mapData.error}</Alert>}
        </Box>
        <Box component="aside" aria-label="Ereignisse und Details" sx={{ display: "flex", flexDirection: "column", minWidth: 0, minHeight: 0, borderLeft: 1, borderColor: "divider", overflow: "hidden" }}>
          {selectedId ? (
            <Box sx={{ height: "100%", minWidth: 0, overflow: "auto", p: 2.5 }}><StoryDetails id={selectedId} onBack={() => setSelectedId(undefined)} /></Box>
          ) : (
            <>
              <Box sx={{ px: 2.5, py: 2, flexShrink: 0 }}>
                <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "baseline", gap: 1 }}><Typography variant="h3">Priorisierte Ereignisse</Typography><Typography color="text.secondary" sx={{ flexShrink: 0, fontSize: "0.75rem" }}>{page ? `${stories.length} von ${page.total}` : "—"}</Typography></Stack>
                <StoryRefreshStatus isLoading={isLoading} lastLoadedAt={lastLoadedAt} onRefresh={refresh} />
                {mapBounds && <Typography variant="body2" color="text.secondary">Ergebnisanzahl im aktiven Kartenausschnitt.</Typography>}
              </Box>
              <Divider />
              {error && <Alert severity="error" sx={{ m: 2 }}>Die Ereignisse konnten nicht geladen werden. {error}</Alert>}
              {page && stories.length === 0 && <StateMessage>{mapBounds ? "Kein Ereignis passt zu diesen Filtern im aktiven Kartenausschnitt. Mit „Zur Weltansicht“ weltweit suchen." : "Kein Ereignis passt zu diesen Filtern."}</StateMessage>}
              <Box role="list" aria-label="Gefilterte Ereignisse" sx={{ flex: 1, minHeight: 0, overflow: "auto" }}>
                {stories.map((story, index) => (
                  <ButtonBase component="article" role="listitem" key={story.id} onClick={() => select(story.id)} sx={{ display: "grid", gridTemplateColumns: "36px 72px minmax(0, 1fr)", gap: 1.5, width: "100%", p: 2.25, textAlign: "left", alignItems: "start", borderBottom: 1, borderColor: "divider", overflowWrap: "anywhere", "&:hover": { bgcolor: "#f8faf9" }, "&.Mui-focusVisible": { outline: "2px solid", outlineColor: "primary.main", outlineOffset: -2 } }}>
                    <Box sx={{ display: "grid", placeItems: "center", width: 34, height: 34, borderRadius: 1, bgcolor: "#eaf1ed", color: "primary.dark", fontSize: "0.75rem", fontWeight: 800 }}>{index + 1}</Box>
                    <Box sx={{ overflow: "hidden", borderRadius: 1 }}>
                      <StoryImage src={story.representativeArticle.mainImageUrl} variant="card" />
                    </Box>
                    <Box sx={{ minWidth: 0 }}>
                      <Typography color="primary.dark" sx={{ fontSize: "0.75rem", fontWeight: 700 }}>{story.location?.name ?? "Ohne Ortsangabe"}</Typography>
                      <Typography variant="h3" sx={{ my: 0.75, fontSize: "1.05rem" }}>{story.representativeArticle.title}</Typography>
                      <Typography color="text.secondary" sx={{ fontSize: "0.75rem" }}><FormattedDate value={story.effectiveTo} /> · {story.memberCount} Artikel · {story.sourceCount} Quellen · {story.representativeArticle.domain}</Typography>
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
