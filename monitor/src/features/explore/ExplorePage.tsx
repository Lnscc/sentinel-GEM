import { Box, Button, Paper, Stack, Typography } from "@mui/material";
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import StoryDetails from "../stories/StoryDetails";
import ExploreFiltersForm, { type ExploreCountry } from "./ExploreFiltersForm";
import ExploreResults, { type ExploreResponse } from "./ExploreResults";
import { exploreHref, exploreSearch, readExploreQuery, type ExploreFilters, type ExploreQuery, type ExploreView } from "./exploreQuery";
import useExploreResource from "./useExploreResource";

type ExplorePosition = { scrollTop: number; triggerId?: string };

export default function ExplorePage() {
  const [query, setQuery] = useState(readExploreQuery);
  const { view, q, country, domain, period, story } = query;
  const scroller = useRef<HTMLElement | null>(null);
  const resultsHeading = useRef<HTMLHeadingElement | null>(null);
  const restorePosition = useRef<ExplorePosition | undefined>(window.history.state?.explorePosition);
  const filters = useMemo(() => ({ q, country, domain, period }), [q, country, domain, period]);
  const { data: countries = [], error: countryError } = useExploreResource<ExploreCountry[]>(`/api/explore/countries?view=${view}`);
  const search = exploreSearch(query).toString();
  const { data, error, retry } = useExploreResource<ExploreResponse>(`/api/explore/${view}${search ? `?${search}` : ""}`);

  useEffect(() => {
    const pop = (event: PopStateEvent) => {
      restorePosition.current = event.state?.explorePosition;
      if (scroller.current) scroller.current.scrollTop = 0;
      setQuery(readExploreQuery());
    };
    window.addEventListener("popstate", pop);
    return () => window.removeEventListener("popstate", pop);
  }, []);

  useLayoutEffect(() => {
    const position = restorePosition.current;
    if (story || !position || !scroller.current || (!data && !error)) return;
    scroller.current.scrollTop = position.scrollTop;
    if (position.triggerId) document.getElementById(position.triggerId)?.focus({ preventScroll: true });
    restorePosition.current = undefined;
  }, [query, story, data, error]);

  const navigate = (next: ExploreQuery, triggerId?: string) => {
    const href = exploreHref(next);
    if (href === window.location.pathname + window.location.search) return;
    const explorePosition = { scrollTop: scroller.current?.scrollTop ?? 0, triggerId };
    window.history.replaceState({ ...window.history.state, explorePosition }, "");
    window.history.pushState(triggerId ? { exploreReturn: true } : null, "", href);
    restorePosition.current = undefined;
    if (scroller.current) scroller.current.scrollTop = 0;
    setQuery(next);
  };

  const setView = (next: ExploreView) => {
    if (next !== view) navigate({ ...query, view: next, page: 1 });
  };

  const apply = (next: ExploreFilters) => {
    if (next.q !== q || next.country !== country || next.domain !== domain || next.period !== period) {
      navigate({ ...query, ...next, page: 1 });
    }
  };

  const closeStory = () => {
    if (window.history.state?.exploreReturn) {
      window.history.back();
      return;
    }
    const next = { ...query, story: "" };
    window.history.replaceState(null, "", exploreHref(next));
    if (scroller.current) scroller.current.scrollTop = 0;
    setQuery(next);
  };

  return (
    <Box component="main" ref={scroller} sx={{ overflowY: "auto", minHeight: 0, flex: "1 1 0", px: { xs: 2, sm: 3, md: 4 }, py: 2.5 }}>
      <Stack spacing={2.5} useFlexGap sx={{ maxWidth: 1240, mx: "auto", minWidth: 0 }}>
        <Stack direction="row" useFlexGap sx={{ alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 2 }}>
          <Typography variant="h1" sx={{ fontSize: "2.5rem", m: 0 }}>Explore</Typography>
          {!story && (
            <Stack direction="row" role="group" aria-label="Ergebnisansicht" sx={{ p: 0.5, gap: 0.5, border: 1, borderColor: "divider", bgcolor: "background.paper", borderRadius: 2 }}>
              {([["stories", "Stories"], ["articles", "Artikel"]] as const).map(([value, label]) => (
                <Button key={value} aria-pressed={view === value} onClick={() => setView(value)} sx={{
                  px: 2,
                  color: view === value ? "primary.dark" : "text.secondary",
                  bgcolor: view === value ? "action.selected" : undefined,
                  fontWeight: 700,
                  textTransform: "none",
                }}>{label}</Button>
              ))}
            </Stack>
          )}
        </Stack>
        {story ? (
          <Paper variant="outlined" sx={{ width: "100%", maxWidth: 880, mx: "auto", p: { xs: 2, sm: 3 } }}>
            <StoryDetails key={story} id={story} onBack={closeStory} backLabel="Zurück zu den Suchergebnissen" focusBack />
          </Paper>
        ) : (
          <Box sx={{ display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", sm: "clamp(216px, 34vw, 270px) minmax(0, 1fr)" }, gap: 2.5, alignItems: "start" }}>
            <Paper component="aside" aria-label="Filter" variant="outlined" sx={{ p: 2, minWidth: 0 }}>
              <ExploreFiltersForm filters={filters} view={view} countries={countries} countryError={countryError} onApply={apply} />
            </Paper>
            <Paper component="section" aria-labelledby="explore-results" variant="outlined" sx={{ minWidth: 0 }}>
              <Typography ref={resultsHeading} id="explore-results" tabIndex={-1} variant="h2" sx={{ px: 2.5, pt: 2, fontSize: "1.45rem" }}>Suchergebnisse</Typography>
              <ExploreResults
                data={data}
                error={error}
                view={view}
                onPage={(page) => {
                  navigate({ ...query, page });
                  resultsHeading.current?.focus({ preventScroll: true });
                }}
                onRetry={retry}
                storyHref={(id) => exploreHref({ ...query, story: id })}
                onStory={(id, triggerId) => navigate({ ...query, story: id }, triggerId)}
              />
            </Paper>
          </Box>
        )}
      </Stack>
    </Box>
  );
}
