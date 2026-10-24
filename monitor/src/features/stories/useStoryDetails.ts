import { useEffect, useState } from "react";
import type { StoryDetail } from "./storyTypes";

export default function useStoryDetails(id: string) {
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

  return { story, error };
}
