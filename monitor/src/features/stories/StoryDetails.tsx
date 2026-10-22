import { useEffect, useState } from "react";
import { Alert, Box, Button, Link, Paper, Stack, Typography } from "@mui/material";
import StateMessage from "../../shared/StateMessage";
import StoryMetadata, { DateRange, FormattedDate } from "./StoryMetadata";
import type { StoryDetail } from "./storyTypes";

function externalUrl(value: unknown) {
  if (typeof value !== "string") return undefined;

  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : undefined;
  } catch {
    return undefined;
  }
}

export default function StoryDetails({ id }: { id: string }) {
  const [story, setStory] = useState<StoryDetail>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    const controller = new AbortController();
    setStory(undefined);
    setError(undefined);

    fetch(`/api/stories/${encodeURIComponent(id)}`, { signal: controller.signal })
      .then((response) => {
        if (response.status === 404) throw new Error("Diese Story wurde nicht gefunden.");
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<StoryDetail>;
      })
      .then(setStory)
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "Unbekannter Fehler");
      });

    return () => controller.abort();
  }, [id]);

  return (
    <>
      <Button component="a" href="#" sx={{ mb: 3, px: 0 }}>← Zurück zur Übersicht</Button>
      {!story && !error && <StateMessage>Story wird geladen …</StateMessage>}
      {error && <Alert severity="error">Die Story konnte nicht geladen werden. {error}</Alert>}
      {story && (
        <Box component="article">
          <Box component="header" sx={{ maxWidth: 704, mb: 4 }}>
            <Typography variant="overline">Story · {story.state}</Typography>
            <Typography variant="h1">{story.representativeArticle.title}</Typography>
            <Typography variant="body1" color="text.secondary">
              Repräsentativer Artikel von {story.representativeArticle.domain}
            </Typography>
          </Box>

          <Paper variant="outlined" sx={{ mb: 6, p: 3 }}>
            <StoryMetadata columns="2fr 1fr 1fr">
              <Box><dt>Zeitraum</dt><dd><DateRange from={story.effectiveFrom} to={story.effectiveTo} /></dd></Box>
              <Box><dt>Status</dt><dd>{story.state}</dd></Box>
              <Box><dt>Artikel</dt><dd>{story.members.length}</dd></Box>
            </StoryMetadata>
          </Paper>

          <Box component="section" aria-labelledby="articles-heading">
            <Typography id="articles-heading" variant="h2" sx={{ mb: 2 }}>Zugehörige Artikel</Typography>
            <Stack spacing={1.5}>
              {story.members.map(({ article }) => {
                const sourceUrl = externalUrl(article.canonicalUrl);

                return (
                  <Paper component="article" variant="outlined" key={article.id} sx={{ minWidth: 0, p: 2.5 }}>
                    <Typography variant="overline" color="primary.main">{article.domain}</Typography>
                    <Typography variant="h3" sx={{ my: 1, overflowWrap: "anywhere" }}>{article.title}</Typography>
                    <Typography color="text.secondary" sx={{ fontSize: "0.9rem" }}>
                      <FormattedDate value={article.publishedAt ?? article.firstSeenAt} />
                    </Typography>
                    {sourceUrl && (
                      <Link
                        href={sourceUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        aria-label={`${article.title}: externe Quelle öffnen (neuer Tab)`}
                        sx={{ display: "inline-block", mt: 1 }}
                      >
                        Quelle öffnen ↗
                      </Link>
                    )}
                  </Paper>
                );
              })}
            </Stack>
          </Box>
        </Box>
      )}
    </>
  );
}
