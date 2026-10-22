package com.example.globalnewsenginev1.stories.query;

import java.time.Instant;

public record StorySearchCriteria(Instant from, Instant to, String domain, String country, String region) {

    public static StorySearchCriteria defaults() {
        return new StorySearchCriteria(null, null, null, null, null);
    }
}
