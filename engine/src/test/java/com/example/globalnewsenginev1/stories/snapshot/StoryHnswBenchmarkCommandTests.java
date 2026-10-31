package com.example.globalnewsenginev1.stories.snapshot;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryHnswBenchmarkCommandTests {

    @Test
    void reranksWithExactCosineAndArticleRefAsDeterministicTieBreaker() {
        Instant now = Instant.parse("2026-10-31T00:00:00Z");
        var query = new StoryHnswBenchmarkCommand.Input("query", now, new float[]{1, 0});
        var candidates = List.of(
                new StoryHnswBenchmarkCommand.Input("b", now, new float[]{1, 0}),
                new StoryHnswBenchmarkCommand.Input("c", now, new float[]{0, 1}),
                new StoryHnswBenchmarkCommand.Input("a", now, new float[]{1, 0}));

        assertThat(StoryHnswBenchmarkCommand.rerank(query, candidates))
                .extracting(StoryHnswBenchmarkCommand.Scored::articleRef)
                .containsExactly("a", "b", "c");
    }
}
