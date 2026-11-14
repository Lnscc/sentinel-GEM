package com.example.globalnewsenginev1.stories.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StoryDetail(
        UUID id,
        String state,
        Instant effectiveFrom,
        Instant effectiveTo,
        StoryLocation location,
        List<StoryLocation> locations,
        StoryArticle representativeArticle,
        List<StoryMember> members
) {
    public StoryDetail {
        locations = List.copyOf(locations);
        members = List.copyOf(members);
    }
}
