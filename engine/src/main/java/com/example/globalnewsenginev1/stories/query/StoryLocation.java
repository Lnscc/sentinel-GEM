package com.example.globalnewsenginev1.stories.query;

public record StoryLocation(
        String name,
        String countryCode,
        String regionCode,
        double latitude,
        double longitude,
        String source,
        String confidence,
        String evidence,
        int supportingArticles,
        int supportingDomains
) {
}
