package com.example.globalnewsenginev1.stories.snapshot;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryHnswShadowServiceTests {

    @Test
    void limitsCandidatesToTheConfiguredTimeWindow() {
        Instant now = Instant.parse("2026-11-02T12:00:00Z");
        var query = input("query", now);

        assertThat(StoryHnswShadowService.candidatesInWindow(query, List.of(
                        input("too-old", now.minusSeconds(24 * 60 * 60 + 1)),
                        input("before-boundary", now.minusSeconds(24 * 60 * 60)),
                        input("after-boundary", now.plusSeconds(24 * 60 * 60)),
                        input("too-new", now.plusSeconds(24 * 60 * 60 + 1))), 24))
                .extracting(StorySnapshotRepository.SnapshotInput::articleRef)
                .containsExactly("before-boundary", "after-boundary");
    }

    @Test
    void limitsTheRollingShadowToTheCurrentWindow() {
        Instant watermark = Instant.parse("2026-11-02T12:00:00Z");

        assertThat(StoryHnswShadowService.currentWindowInputs(watermark, List.of(
                        input("too-old", watermark.minusSeconds(24 * 60 * 60 + 1)),
                        input("boundary", watermark.minusSeconds(24 * 60 * 60)),
                        input("current", watermark),
                        input("future", watermark.plusSeconds(1))), 24))
                .extracting(StorySnapshotRepository.SnapshotInput::articleRef)
                .containsExactly("boundary", "current");
    }

    private static StorySnapshotRepository.SnapshotInput input(String articleRef, Instant effectiveAt) {
        return new StorySnapshotRepository.SnapshotInput(
                1, articleRef, "fingerprint", effectiveAt, "source", "title-hash",
                1, "vector-hash", new byte[0], 1536);
    }
}
