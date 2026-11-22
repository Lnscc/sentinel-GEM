import { Box, Link, Stack, Typography } from "@mui/material";
import type { MouseEventHandler } from "react";
import externalUrl from "../../shared/externalUrl";
import { DateRange, FormattedDate } from "../stories/StoryMetadata";
import type { ExploreCountry } from "./ExploreFiltersForm";

export type StoryResult = {
  id: string;
  title: string | null;
  domain: string | null;
  startedAt: string | null;
  lastSeenAt: string | null;
  articleCount: number;
  locationName: string | null;
};

export type ArticleResult = {
  id: number;
  title: string | null;
  url: string;
  domain: string | null;
  firstSeenAt: string;
  mentionedCountries: ExploreCountry[];
  storyId: string | null;
};

type StoryLinkProps = { id: string; href: string; onClick: MouseEventHandler<HTMLAnchorElement> };
export type StoryLink = (storyId: string, triggerId: string) => StoryLinkProps;

const row = { px: 2.5, py: 1.75, borderTop: 1, borderColor: "divider", overflowWrap: "anywhere" } as const;
const headline = { fontSize: "1.15rem", lineHeight: 1.35, my: 0.4 } as const;

export function ExploreStoryItem({ item, storyLink }: { item: StoryResult; storyLink: StoryLink }) {
  return (
    <Box component="li" sx={row}>
      <Typography variant="caption" color="primary.dark" sx={{ fontWeight: 750 }}>
        {item.locationName || "Ort unbekannt"}
      </Typography>
      <Typography component="h3" variant="h3" sx={headline}>
        <Link {...storyLink(item.id, `explore-story-${item.id}`)} color="text.primary" underline="hover">
          {item.title?.trim() || "Story ohne Titel"}{"\u00a0"}
          <Box component="span" aria-hidden="true" sx={{ color: "primary.main" }}>→</Box>
        </Link>
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {item.startedAt && item.lastSeenAt ? <DateRange from={item.startedAt} to={item.lastSeenAt} /> : "Zeitraum unbekannt"}
        {" · "}{item.articleCount} Artikel
      </Typography>
    </Box>
  );
}

export function ExploreArticleItem({ item, storyLink }: { item: ArticleResult; storyLink: StoryLink }) {
  const url = externalUrl(item.url);

  return (
    <Box component="li" sx={row}>
      <Typography variant="body2" color="text.secondary">
        <Box component="strong" sx={{ color: "primary.dark" }}>{item.domain || "Quelle unbekannt"}</Box>
        {" · Erstmals erfasst am "}<FormattedDate value={item.firstSeenAt} />
      </Typography>
      <Typography component="h3" variant="h3" sx={headline}>{item.title?.trim() || item.url || "Artikel ohne Titel"}</Typography>
      <Typography variant="body2" color="text.secondary">
        Erwähnte Länder: {item.mentionedCountries.map(({ name }) => name).join(", ") || "Keine Zuordnung"}
      </Typography>
      <Stack direction="row" useFlexGap spacing={2} sx={{ flexWrap: "wrap", mt: 1 }}>
        {url && <Link href={url} target="_blank" rel="noopener noreferrer" variant="body2" underline="hover">Originalquelle öffnen <span aria-hidden="true">↗</span></Link>}
        {item.storyId
          ? <Link {...storyLink(item.storyId, `explore-article-story-${item.id}`)} variant="body2" underline="hover">Zugehörige Story öffnen</Link>
          : <Typography variant="body2" color="text.secondary">Keiner Story zugeordnet</Typography>}
      </Stack>
    </Box>
  );
}
