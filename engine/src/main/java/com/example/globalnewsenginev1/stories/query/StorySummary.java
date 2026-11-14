package com.example.globalnewsenginev1.stories.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StorySummary(
        UUID id,
        String state,
        Instant effectiveFrom,
        Instant effectiveTo,
        StoryLocation location,
        List<StoryLocation> locations,
        StoryArticle representativeArticle,
        long memberCount,
        long sourceCount
) {
    public StorySummary {
        locations = List.copyOf(locations);
    }
}
