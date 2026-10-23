import { useEffect, useState } from "react";
import { Alert, Box, Button, Link, Paper, Stack, Typography } from "@mui/material";
import StateMessage from "../../shared/StateMessage";
import StoryImage from "./StoryImage";
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

export default function StoryDetails({ id, onBack }: { id: string; onBack: () => void }) {
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
      <Button onClick={onBack} sx={{ mb: 2, px: 0 }}>← Alle Ereignisse</Button>
      {!story && !error && <StateMessage>Ereignis wird geladen …</StateMessage>}
      {error && <Alert severity="error">Das Ereignis konnte nicht geladen werden. {error}</Alert>}
      {story && (
        <Box component="article">
          <Box component="header" sx={{ mb: 3 }}>
            <Typography variant="overline">Ereignis · {story.state}</Typography>
            <Typography variant="h2" sx={{ my: 1 }}>{story.representativeArticle.title}</Typography>
            <Typography variant="body1" color="text.secondary">
              Repräsentativer Artikel von {story.representativeArticle.domain}
            </Typography>
            <StoryImage src={story.representativeArticle.mainImageUrl} variant="detail" />
          </Box>

          <Paper variant="outlined" sx={{ mb: 3, p: 2 }}>
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
                    <Typography variant="body1" sx={{ my: 0.5, overflowWrap: "anywhere", fontWeight: 700 }}>{article.title}</Typography>
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
