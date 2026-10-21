import { useEffect, useState } from "react";
import { Container } from "@mui/material";
import StoryDetails from "./StoryDetails";
import StoryOverview from "./StoryOverview";

export default function App() {
  const [selectedStoryId, setSelectedStoryId] = useState(() => window.location.hash.slice(1));

  useEffect(() => {
    const selectStory = () => setSelectedStoryId(window.location.hash.slice(1));
    window.addEventListener("hashchange", selectStory);
    return () => window.removeEventListener("hashchange", selectStory);
  }, []);

  return (
    <Container component="main" maxWidth="lg" sx={{ py: { xs: 5, sm: 8 } }}>
      {selectedStoryId ? <StoryDetails id={selectedStoryId} /> : <StoryOverview />}
    </Container>
  );
}
