package com.example.globalnewsenginev1.stories;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Provider usage is process-local, just like the existing call and latency meters. */
@Component
public class EmbeddingUsageMetrics {
    private final MeterRegistry registry;
    private final Instant from = Instant.now();
    private long measuredResponses;
    private long measuredTokens;

    public EmbeddingUsageMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public synchronized void recordUsage(Long tokens) {
        if (tokens != null && tokens >= 0) {
            measuredResponses++;
            measuredTokens += tokens;
        }
    }

    public synchronized Snapshot snapshot() {
        var counter = registry.find("stories.embedding.model.calls").counter();
        var latency = registry.find("stories.embedding.provider.latency").timer();
        long calls = counter == null ? 0 : (long) counter.count();
        long withoutUsage = Math.max(0, calls - measuredResponses);
        return new Snapshot(from, calls,
                calls == 0 || withoutUsage > 0 ? null : measuredTokens,
                latency == null || latency.count() == 0 ? null : latency.totalTime(TimeUnit.SECONDS),
                withoutUsage);
    }

    public record Snapshot(Instant from, long calls, Long tokens, Double durationSeconds,
                           long callsWithoutUsage) {
    }
}
