package com.example.globalnewsenginev1.stories.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "stories.snapshots.benchmark.watermark")
class StorySnapshotBenchmarkCommand implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorySnapshotBenchmarkCommand.class);

    private final StorySnapshotService service;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    StorySnapshotBenchmarkCommand(StorySnapshotService service, Environment environment,
                                  ConfigurableApplicationContext context) {
        this.service = service;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Instant started = Instant.now();
        try {
            Instant watermark = Instant.parse(environment.getRequiredProperty(
                    "stories.snapshots.benchmark.watermark"));
            StorySnapshotService.ProcessingResult result = service.reprocess(watermark, 1);
            LOGGER.info("Story snapshot benchmark completed: watermark={}, elapsed={}, selected={}, "
                            + "succeeded={}, reused={}, failed={}",
                    watermark, Duration.between(started, Instant.now()), result.selectedVersions(),
                    result.succeededVersions(), result.reusedVersions(), result.failedVersions());
        } finally {
            context.close();
        }
    }
}
