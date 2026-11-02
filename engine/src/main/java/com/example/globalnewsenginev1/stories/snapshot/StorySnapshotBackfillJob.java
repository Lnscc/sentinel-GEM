package com.example.globalnewsenginev1.stories.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@ConditionalOnProperty(name = "stories.snapshots.backfill.enabled", havingValue = "true")
class StorySnapshotBackfillJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StorySnapshotBackfillJob.class);

    private final StorySnapshotService service;
    private final int maxVersionsPerRun;
    private final Instant watermark;

    StorySnapshotBackfillJob(
            StorySnapshotService service,
            @Value("${stories.snapshots.backfill.max-versions-per-run:3}")
            int maxVersionsPerRun,
            @Value("${stories.snapshots.backfill.watermark}") Instant watermark
    ) {
        this.service = service;
        this.maxVersionsPerRun = maxVersionsPerRun;
        this.watermark = watermark;
    }

    @Scheduled(
            initialDelayString = "${stories.snapshots.backfill.initial-delay:PT2M}",
            fixedDelayString = "${stories.snapshots.backfill.poll-interval:PT1H}"
    )
    void processBackfill() {
        long startedAt = System.nanoTime();
        StorySnapshotService.ProcessingResult result =
                service.processBackfill(watermark, maxVersionsPerRun);
        if (result.selectedVersions() > 0) {
            LOGGER.info("Story snapshot backfill completed: watermark={}, selected={}, "
                            + "succeeded={}, reused={}, failed={}, durationMs={}",
                    watermark, result.selectedVersions(), result.succeededVersions(),
                    result.reusedVersions(), result.failedVersions(), elapsedMillis(startedAt));
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
