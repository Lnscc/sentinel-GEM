import { Alert, Box, Button, Pagination, Stack, Typography } from "@mui/material";
import StateMessage from "../../shared/StateMessage";
import { DateRange } from "../stories/StoryMetadata";
import { ExploreArticleItem, ExploreStoryItem, type ArticleResult, type StoryLink, type StoryResult } from "./ExploreResultItem";
import type { ExploreView } from "./exploreQuery";

export type ExploreResponse = {
  items: (StoryResult | ArticleResult)[];
  total: number;
  page: number;
  pageSize: number;
  availableFrom: string | null;
  availableTo: string | null;
};

const numberFormat = new Intl.NumberFormat("de-DE");
const paginationLabels: Record<string, string> = {
  first: "Erste Seite",
  previous: "Vorherige Seite",
  next: "Nächste Seite",
  last: "Letzte Seite",
};

export default function ExploreResults({
  data, error, view, onPage, onRetry, storyHref, onStory,
}: {
  data?: ExploreResponse;
  error?: string;
  view: ExploreView;
  onPage: (page: number) => void;
  onRetry: () => void;
  storyHref: (id: string) => string;
  onStory: (id: string, triggerId: string) => void;
}) {
  if (error) return (
    <Alert sx={{ m: 2.5 }} severity="error" action={<Button color="inherit" onClick={onRetry}>Erneut laden</Button>}>
      Die Suchergebnisse konnten nicht geladen werden. {error}
    </Alert>
  );
  if (!data) return <Box sx={{ p: 2.5 }}><StateMessage>Suchergebnisse werden geladen …</StateMessage></Box>;

  const { items, total, page, pageSize, availableFrom, availableTo } = data;
  const lastPage = Math.max(1, Math.ceil(total / pageSize));
  const outside = page > lastPage;
  const storyLink: StoryLink = (storyId, triggerId) => ({
    id: triggerId,
    href: storyHref(storyId),
    onClick: (event) => {
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      onStory(storyId, triggerId);
    },
  });

  return (
    <>
      <Box role="status" sx={{ px: 2.5, pt: 1, pb: 1.5 }}>
        <Stack direction="row" useFlexGap sx={{ justifyContent: "space-between", flexWrap: "wrap", gap: 0.5, mb: 0.5 }}>
          <Typography variant="body2" sx={{ fontWeight: 750 }}>{numberFormat.format(total)} Treffer</Typography>
          <Typography variant="body2" color="text.secondary">Neueste zuerst</Typography>
        </Stack>
        <Typography variant="caption" component="p" color="text.secondary" sx={{ m: 0 }}>
          {availableFrom && availableTo ? (
            <>Gespeicherter Bestand ({view === "stories" ? "Story-Zeitraum" : "Ersterfassung"}): <DateRange from={availableFrom} to={availableTo} />. Die Daten können Lücken enthalten.</>
          ) : "Für diese Ansicht sind keine Daten mit Zeitangaben gespeichert."}
        </Typography>
      </Box>
      {outside && (
        <Box sx={{ p: 2.5, borderTop: 1, borderColor: "divider" }}>
          <Typography>Seite {numberFormat.format(page)} liegt außerhalb der verfügbaren Treffer.</Typography>
          <Button onClick={() => onPage(1)} sx={{ px: 0, mt: 1 }}>Zur ersten Seite</Button>
        </Box>
      )}
      {!outside && total === 0 && (
        <Box sx={{ px: 2.5, pb: 2.5 }}><StateMessage>Keine Treffer für diese Filter.</StateMessage></Box>
      )}
      {items.length > 0 && (
        <Box component="ul" aria-label="Suchergebnisse" sx={{ m: 0, p: 0, listStyle: "none" }}>
          {items.map((item) => "articleCount" in item
            ? <ExploreStoryItem key={item.id} item={item} storyLink={storyLink} />
            : <ExploreArticleItem key={item.id} item={item} storyLink={storyLink} />)}
        </Box>
      )}
      {!outside && lastPage > 1 && (
        <Pagination
          aria-label="Ergebnisseiten"
          page={page}
          count={lastPage}
          showFirstButton
          showLastButton
          getItemAriaLabel={(type, value) => type === "page" ? `Seite ${value}` : paginationLabels[type] ?? ""}
          onChange={(_, value) => onPage(value)}
          sx={{ px: 2, py: 1.5, borderTop: 1, borderColor: "divider" }}
        />
      )}
    </>
  );
}
