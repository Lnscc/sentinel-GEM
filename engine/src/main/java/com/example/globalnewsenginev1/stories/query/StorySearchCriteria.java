package com.example.globalnewsenginev1.stories.query;

import java.time.Instant;

public record StorySearchCriteria(Instant from, Instant to, String query, String domain, String country,
                                  String region, Bounds bounds) {

    public static StorySearchCriteria defaults() {
        return new StorySearchCriteria(null, null, null, null, null, null, null);
    }

    public record Bounds(double west, double south, double east, double north) {
    }
}
