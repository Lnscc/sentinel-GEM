import { useEffect, useState } from "react";
import { Alert, Box, Button, FormControl, InputLabel, MenuItem, Select, Stack, Typography } from "@mui/material";
import StateMessage from "../../shared/StateMessage";
import StoryCard from "./StoryCard";
import StoryFilters, { type StoryFilterValues } from "./StoryFilters";
import LiveMap from "../live-map/LiveMap";
import type { StoryPage } from "./storyTypes";

type Sort = "time" | "articleCount";
type Direction = "asc" | "desc";

const PAGE_SIZE = 20;

export default function StoryOverview() {
  const [page, setPage] = useState<StoryPage>();
  const [error, setError] = useState<string>();
  const [offset, setOffset] = useState(0);
  const [sort, setSort] = useState<Sort>("time");
  const [direction, setDirection] = useState<Direction>("desc");
  const [filters, setFilters] = useState<StoryFilterValues>({});

  useEffect(() => {
    const controller = new AbortController();
    const query = new URLSearchParams({ offset: String(offset), limit: String(PAGE_SIZE), sort, direction });
    if (filters.from) query.set("from", filters.from);
    if (filters.to) query.set("to", filters.to);
    if (filters.domain) query.set("domain", filters.domain);
    if (filters.country) query.set("country", filters.country);
    if (filters.region) query.set("region", filters.region);
    setPage(undefined);
    setError(undefined);

    fetch(`/api/stories?${query}`, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<StoryPage>;
      })
      .then(setPage)
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "Unbekannter Fehler");
      });

    return () => controller.abort();
  }, [offset, sort, direction, filters]);

  const changeSort = (next: Sort) => { setSort(next); setOffset(0); };
  const changeDirection = (next: Direction) => { setDirection(next); setOffset(0); };
  const applyFilters = (next: StoryFilterValues) => { setFilters(next); setOffset(0); };

  return (
    <>
      <Box component="header" sx={{ maxWidth: 704, mb: { xs: 4, sm: 6 } }}>
        <Typography variant="overline">Sentinel GEM</Typography>
        <Typography variant="h1">Aktuelle Stories</Typography>
        <Typography variant="body1" color="text.secondary">
          Nachrichtenereignisse aus verschiedenen Quellen, gebündelt im Überblick.
        </Typography>
      </Box>

      <StoryFilters onApply={applyFilters} />

      <Stack component="section" direction={{ xs: "column", sm: "row" }} spacing={2} sx={{ mb: 3 }} aria-label="Story-Sortierung">
        <FormControl size="small" sx={{ minWidth: 180 }}>
          <InputLabel id="sort-label">Sortieren nach</InputLabel>
          <Select labelId="sort-label" label="Sortieren nach" value={sort} onChange={(event) => changeSort(event.target.value as Sort)}>
            <MenuItem value="time">Zeitpunkt</MenuItem>
            <MenuItem value="articleCount">Artikelanzahl</MenuItem>
          </Select>
        </FormControl>
        <FormControl size="small" sx={{ minWidth: 180 }}>
          <InputLabel id="direction-label">Reihenfolge</InputLabel>
          <Select labelId="direction-label" label="Reihenfolge" value={direction} onChange={(event) => changeDirection(event.target.value as Direction)}>
            <MenuItem value="desc">Absteigend</MenuItem>
            <MenuItem value="asc">Aufsteigend</MenuItem>
          </Select>
        </FormControl>
      </Stack>

      {!page && !error && <StateMessage>Stories werden geladen …</StateMessage>}
      {error && <Alert severity="error">Die Stories konnten nicht geladen werden. {error}</Alert>}
      {page?.stories.length === 0 && <StateMessage>Derzeit sind keine Stories verfügbar.</StateMessage>}
      {page && page.stories.length > 0 && (
        <>
          <LiveMap stories={page.stories} />
          <Box component="section" aria-label="Story-Liste">
            <Typography variant="overline" sx={{ display: "block", mb: 2 }}>{page.total} Stories insgesamt</Typography>
            <Box sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(min(100%, 20rem), 1fr))", gap: 2 }}>
              {page.stories.map((story) => <StoryCard key={story.id} story={story} />)}
            </Box>
            <Stack component="nav" direction="row" spacing={2} sx={{ alignItems: "center", justifyContent: "space-between", mt: 3 }} aria-label="Story-Seiten">
              <Button variant="outlined" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>Zurück</Button>
              <Typography>Seite {offset / PAGE_SIZE + 1} von {Math.ceil(page.total / PAGE_SIZE)}</Typography>
              <Button variant="outlined" disabled={offset + PAGE_SIZE >= page.total} onClick={() => setOffset(offset + PAGE_SIZE)}>Weiter</Button>
            </Stack>
          </Box>
        </>
      )}
    </>
  );
}
