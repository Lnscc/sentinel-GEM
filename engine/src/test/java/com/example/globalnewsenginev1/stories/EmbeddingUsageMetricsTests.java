package com.example.globalnewsenginev1.stories;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingUsageMetricsTests {
    @Test
    void reusesProviderMetersAndNeverTreatsMissingUsageAsZero() {
        var registry = new SimpleMeterRegistry();
        var usage = new EmbeddingUsageMetrics(registry);
        assertThat(usage.snapshot().tokens()).isNull();
        assertThat(usage.snapshot().durationSeconds()).isNull();

        registry.counter("stories.embedding.model.calls").increment();
        registry.timer("stories.embedding.provider.latency").record(Duration.ofMillis(500));
        usage.recordUsage(23L);
        assertThat(usage.snapshot().calls()).isEqualTo(1);
        assertThat(usage.snapshot().tokens()).isEqualTo(23);
        assertThat(usage.snapshot().durationSeconds()).isEqualTo(0.5);

        registry.counter("stories.embedding.model.calls").increment();
        usage.recordUsage(null);
        usage.recordUsage(-1L);
        assertThat(usage.snapshot().tokens()).isNull();
        assertThat(usage.snapshot().callsWithoutUsage()).isEqualTo(1);
        assertThat(new EmbeddingUsageMetrics(new SimpleMeterRegistry()).snapshot().tokens()).isNull();
    }
}
