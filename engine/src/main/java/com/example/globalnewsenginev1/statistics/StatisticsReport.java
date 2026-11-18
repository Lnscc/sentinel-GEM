package com.example.globalnewsenginev1.statistics;

import java.time.Instant;
import java.util.List;

public record StatisticsReport(Instant generatedAt, Period period, String visibleVersion, String visibleVersionStatus,
                               List<Metric> metrics, List<Run> recentRuns) {
    public record Period(Instant from, Instant to) {}

    public record Metric(String id, String group, String label, Double value, String unit,
                         String scope, String definition, Instant from, Instant to,
                         String unavailableReason) {}

    public record Run(String kind, String status, Instant startedAt, Instant completedAt) {}
}
