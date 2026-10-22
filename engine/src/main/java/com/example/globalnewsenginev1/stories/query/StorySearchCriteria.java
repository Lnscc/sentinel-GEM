package com.example.globalnewsenginev1.stories.query;

import java.time.Instant;

public record StorySearchCriteria(Instant from, Instant to, String domain) {

    public static StorySearchCriteria defaults() {
        return new StorySearchCriteria(null, null, null);
    }
}
