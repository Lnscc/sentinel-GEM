package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Comparison-only synthetic benchmark; neither database loading nor GEM-124 pipeline acceptance. */
class StoryExactComparisonBenchmarkTests {

    @Test
    void measuresDenseExactIncrementalComparison() throws Exception {
        assumeTrue(Boolean.getBoolean("story.exact.benchmark"), "Explicit dense benchmark opt-in required");
        int count = Integer.getInteger("story.exact.inputs", 107_000);
        int changes = Integer.getInteger("story.exact.changes", 1_000);
        int dimension = Integer.getInteger("story.exact.dimension", 1_536);
        int repetitions = Integer.getInteger("story.exact.repetitions", 3);
        assertThat(changes).isPositive();
        assertThat(count).isGreaterThanOrEqualTo(changes * 2);
        assertThat(dimension).isGreaterThanOrEqualTo(2);
        assertThat(repetitions).isPositive();
        Instant epoch = Instant.parse(System.getProperty("story.exact.epoch", "2026-11-01T12:00:00Z"));
        long prepareStarted = System.nanoTime();
        Fixture fixture = fixture(count, changes, dimension, epoch);
        double prepareMs = (System.nanoTime() - prepareStarted) / 1_000_000.0;
        var version = new StorySnapshotRepository.ClusteringVersion(1, "gem127-dense-exact", dimension,
                24, new BigDecimal("0.700000"), "exact-cosine-radius-v1", "cosine-070-time24-v1");
        var rules = new StoryPartitionService.SnapshotRules(1, "gem127-frozen", "gem127-frozen-hash",
                version.key(), dimension, 24, version.threshold(), version.searchMode(),
                StoryPartitionService.COMPONENT_RULE);
        String inputHash = StorySnapshotCanonicalizer.snapshotInputHash(version.key(), epoch, fixture.inputs());
        List<Object> runs = new ArrayList<>();
        String expectedDigest = null;
        for (int repetition = 0; repetition < repetitions; repetition++) {
            var run = measure(version, rules, fixture, fixture.changed());
            assertThat(run.candidates()).isEqualTo((long) changes * (count - 1));
            assertThat(run.components()).isEqualTo(changes - Math.min(50, changes / 2));
            if (expectedDigest == null) expectedDigest = run.digest();
            assertThat(run.digest()).as("Fresh-search retry semantics").isEqualTo(expectedDigest);
            runs.add(run);
        }
        var noop = measure(version, rules, fixture, Set.of());
        assertThat(noop.candidates()).isZero();
        assertThat(noop.components()).isZero();
        var report = new LinkedHashMap<String, Object>();
        report.put("variant", System.getProperty("story.exact.variant", "unspecified"));
        report.put("sourceCommit", System.getProperty("story.exact.source", "unspecified"));
        report.put("scope", "Synthetic in-memory exact comparison only; no database load/publication measured");
        report.put("logicalInputs", count);
        report.put("changedInputs", changes);
        report.put("dimension", dimension);
        report.put("epoch", epoch.toString());
        report.put("inputHash", inputHash);
        report.put("vectorPayloadBytes", (long) count * dimension * Float.BYTES);
        report.put("fixturePreparationMs", prepareMs);
        report.put("runs", runs);
        report.put("noop", noop);
        report.put("javaRuntime", System.getProperty("java.runtime.version"));
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
        report.put("notes", "Seeded independent dense random vectors plus near-identical partners. "
                + "Every changed input sees every other input. Candidate dot products and their dimension-products "
                + "exclude additional coherence/member-evidence cosines. Heap sampled every 5ms during comparison; "
                + "pool peak sum is an upper bound, not simultaneous usage. Repetitions are fresh-search retries "
                + "over the same frozen state; cold and warm runs retained separately. No embedding calls or DB access.");
        Path path = Path.of(System.getProperty("story.exact.output", "target/gem127-benchmark/report.json"));
        Files.createDirectories(path.toAbsolutePath().getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        System.out.println("GEM127_EXACT_REPORT " + path.toAbsolutePath());
    }

    private Run measure(StorySnapshotRepository.ClusteringVersion version,
                        StoryPartitionService.SnapshotRules rules, Fixture fixture, Set<String> changed) {
        System.gc();
        var pools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP).toList();
        pools.forEach(pool -> pool.resetPeakUsage());
        long heapBefore = heapUsed();
        var peak = new AtomicLong(heapBefore);
        var threadBean = ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isCurrentThreadCpuTimeSupported()).isTrue();
        if (!threadBean.isThreadCpuTimeEnabled()) threadBean.setThreadCpuTimeEnabled(true);
        var osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        StoryPartitionService.Partition partition;
        StorySnapshotService.PairSearchResult result;
        long compareNanos;
        long threadCpuNanos;
        long processCpuNanos;
        try (var sampler = Executors.newSingleThreadScheduledExecutor()) {
            sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(heapUsed(), Math::max),
                    0, 5, TimeUnit.MILLISECONDS);
            var search = new StoryIncrementalPairSearch(version, fixture.inputs());
            long cpuBefore = threadBean.getCurrentThreadCpuTime();
            long processBefore = osBean.getProcessCpuTime();
            long start = System.nanoTime();
            partition = StoryPartitionService.incrementalPartition(rules, fixture.inputs(), search,
                    changed, fixture.assignments());
            result = search.result();
            compareNanos = System.nanoTime() - start;
            threadCpuNanos = threadBean.getCurrentThreadCpuTime() - cpuBefore;
            processCpuNanos = osBean.getProcessCpuTime() - processBefore;
            peak.accumulateAndGet(heapUsed(), Math::max);
        }
        StringBuilder semantic = new StringBuilder(partition.components().toString());
        for (var decision : result.decisions()) {
            semantic.append('\n').append(decision.left().articleRef()).append(':')
                    .append(decision.right().articleRef()).append(':').append(decision.similarity())
                    .append(':').append(decision.timeDistanceSeconds()).append(':').append(decision.rank())
                    .append(':').append(decision.result()).append(':').append(decision.triggeredRule())
                    .append(':').append(decision.topOneBelowThreshold());
        }
        return new Run(compareNanos / 1_000_000.0, threadCpuNanos / 1_000_000.0,
                processCpuNanos / 1_000_000.0, result.comparedPairs(),
                result.comparedPairs() * version.dimension(), partition.components().size(),
                result.decisions().size(), StorySnapshotCanonicalizer.sha256(
                semantic.toString().getBytes(StandardCharsets.UTF_8)), heapBefore, peak.get(),
                pools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum());
    }

    private Fixture fixture(int count, int changes, int dimension, Instant epoch) {
        var random = new Random(127_2026);
        var inputs = new ArrayList<StorySnapshotRepository.SnapshotInput>(count);
        var changed = new HashSet<String>();
        var assignments = new ArrayList<StorySnapshotRepository.ExistingAssignment>();
        int pairedChanges = Math.min(100, changes / 2 * 2);
        for (int i = 0; i < count; i++) {
            float[] values = new float[dimension];
            if (i >= count - changes) {
                int offset = i - (count - changes);
                int partner = offset < pairedChanges ? offset / 2 : offset;
                float[] original = inputs.get(partner).decodedVector();
                for (int d = 0; d < dimension; d++) values[d] = original[d] + random.nextFloat() * 0.01f;
            } else {
                for (int d = 0; d < dimension; d++) values[d] = random.nextFloat() * 2 - 1;
            }
            byte[] bytes = new byte[dimension * Float.BYTES];
            var buffer = ByteBuffer.wrap(bytes);
            for (float value : values) buffer.putFloat(value);
            String ref = String.format("%064x", i + 1);
            inputs.add(new StorySnapshotRepository.SnapshotInput(i + 1, ref, ref,
                    epoch.plusMillis(i), "PUBLISHED_AT", "Synthetic dense news article " + i,
                    ref, i + 1, StorySnapshotCanonicalizer.sha256(bytes), null, dimension, values));
            if (i >= count - changes) {
                changed.add(ref);
            } else {
                assignments.add(new StorySnapshotRepository.ExistingAssignment(ref,
                        UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8)), ref));
            }
        }
        return new Fixture(List.copyOf(inputs), Set.copyOf(changed), List.copyOf(assignments));
    }

    private long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private record Fixture(List<StorySnapshotRepository.SnapshotInput> inputs, Set<String> changed,
                           List<StorySnapshotRepository.ExistingAssignment> assignments) { }

    private record Run(double compareMs, double currentThreadCpuMs, double processCpuMs,
                       long candidates, long candidateDimensionProducts, int components, int decisions,
                       String digest, long heapBeforeBytes, long heapSampledPeakBytes, long heapPoolPeakSumBytes) { }
}
