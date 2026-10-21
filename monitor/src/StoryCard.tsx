import { Box, Card, CardActionArea, CardContent, Typography } from "@mui/material";
import StoryMetadata, { DateRange } from "./StoryMetadata";
import type { Story } from "./storyTypes";

export default function StoryCard({ story }: { story: Story }) {
  return (
    <Card component="article" variant="outlined" sx={{ minWidth: 0 }}>
      <CardActionArea
        component="a"
        href={`#${story.id}`}
        sx={{ display: "flex", minHeight: { xs: 0, sm: 272 }, height: "100%", alignItems: "stretch" }}
      >
        <CardContent sx={{ display: "flex", minWidth: 0, width: "100%", flexDirection: "column", p: 3 }}>
          <Typography variant="overline" color="primary.main">{story.representativeArticle.domain}</Typography>
          <Typography variant="h2" sx={{ my: 2, overflowWrap: "anywhere" }}>
            {story.representativeArticle.title}
          </Typography>
          <Box sx={{ mt: "auto" }}>
            <StoryMetadata>
              <Box><dt>Zeitraum</dt><dd><DateRange from={story.effectiveFrom} to={story.effectiveTo} /></dd></Box>
              <Box><dt>Artikel</dt><dd>{story.memberCount}</dd></Box>
            </StoryMetadata>
            <Typography sx={{ mt: 2.5, color: "primary.main", fontWeight: 700 }}>
              Story öffnen <span aria-hidden="true">→</span>
            </Typography>
          </Box>
        </CardContent>
      </CardActionArea>
    </Card>
  );
}
