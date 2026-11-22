import { Box, Link, Typography } from "@mui/material";
import ExplorePage from "./features/explore/ExplorePage";
import StoryOverview from "./features/stories/StoryOverview";
import StatisticsPage from "./features/statistics/StatisticsPage";

export default function App() {
  const isExplore = /^\/explore\/?$/.test(window.location.pathname);
  const isStatistics = /^\/statistics\/?$/.test(window.location.pathname);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100dvh", minWidth: 0, overflow: "hidden" }}>
      <Box component="header" sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", columnGap: { xs: 2, sm: 4 }, rowGap: 1, minHeight: 60, px: { xs: 2, sm: 3, md: 4 }, py: 1, bgcolor: "background.paper", borderBottom: 1, borderColor: "divider", flexShrink: 0 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
          <Box aria-hidden="true" sx={{ display: "grid", placeItems: "center", width: 28, height: 28, borderRadius: "9px", bgcolor: "primary.main", color: "common.white", fontWeight: 800 }}>S</Box>
          <Typography sx={{ fontWeight: 800 }}>Sentinel GEM</Typography>
        </Box>
        <Box component="nav" aria-label="Hauptnavigation" sx={{
          display: "flex",
          flexWrap: "wrap",
          gap: 0.5,
          "& a": {
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            minHeight: 40,
            px: 1.75,
            borderRadius: 1,
            color: "text.secondary",
            fontSize: "0.875rem",
            fontWeight: 700,
            "&:hover": { bgcolor: "action.hover", color: "text.primary" },
            "&[aria-current='page']": { bgcolor: "primary.main", color: "primary.contrastText" },
            "&:focus-visible": { outline: "2px solid", outlineColor: "primary.dark", outlineOffset: 2 },
          },
        }}>
          <Link href="/" underline="none" aria-current={!isExplore && !isStatistics ? "page" : undefined}>Lagebild</Link>
          <Link href="/explore" underline="none" aria-current={isExplore ? "page" : undefined}>Explore</Link>
          <Link href="/statistics" underline="none" aria-current={isStatistics ? "page" : undefined}>Statistik</Link>
        </Box>
      </Box>
      {isStatistics ? <StatisticsPage /> : isExplore ? <ExplorePage /> : <StoryOverview />}
    </Box>
  );
}
