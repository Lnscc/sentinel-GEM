package com.example.globalnewsenginev1.stories.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

@Service
@ConditionalOnProperty(name = "stories.snapshots.incremental.enabled",
        havingValue = "true", matchIfMissing = true)
class StorySnapshotJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorySnapshotJob.class);

    private final StorySnapshotService service;
    private final int maxVersionsPerRun;

    StorySnapshotJob(
            StorySnapshotService service,
            @Value("${stories.snapshots.max-versions-per-run:4}") int maxVersionsPerRun
    ) {
        this.service = service;
        this.maxVersionsPerRun = maxVersionsPerRun;
    }

    @Scheduled(
            initialDelayString = "${stories.snapshots.incremental.initial-delay:PT1M}",
            fixedDelayString = "${stories.snapshots.incremental.poll-interval:PT5M}"
    )
    void processIncremental() {
        long startedAt = System.nanoTime();
        long heapUsedBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        StorySnapshotService.ProcessingResult result =
                service.processIncremental(maxVersionsPerRun);
        if (result.selectedVersions() > 0) {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            LOGGER.info("Story snapshot run completed: selected={}, succeeded={}, reused={}, failed={}, durationMs={}, "
                            + "heapUsedBeforeBytes={}, heapUsedAfterBytes={}, heapMaxBytes={}",
                    result.selectedVersions(), result.succeededVersions(),
                    result.reusedVersions(), result.failedVersions(), elapsedMillis(startedAt),
                    heapUsedBefore, heap.getUsed(), heap.getMax());
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
