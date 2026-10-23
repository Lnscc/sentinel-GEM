import { Box, Typography } from "@mui/material";
import StoryOverview from "./features/stories/StoryOverview";

export default function App() {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100dvh", minWidth: 960, overflow: "hidden" }}>
      <Box component="header" sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", height: 60, px: 4, bgcolor: "background.paper", borderBottom: 1, borderColor: "divider", flexShrink: 0 }}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
          <Box aria-hidden="true" sx={{ display: "grid", placeItems: "center", width: 28, height: 28, borderRadius: "9px", bgcolor: "primary.main", color: "common.white", fontWeight: 800 }}>S</Box>
          <Typography sx={{ fontWeight: 800 }}>Sentinel GEM</Typography>
        </Box>
        <Typography color="text.secondary" sx={{ fontSize: "0.8rem" }}>Globale Ereignisse im Überblick</Typography>
      </Box>
      <StoryOverview />
    </Box>
  );
}
