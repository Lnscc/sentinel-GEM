package com.example.globalnewsenginev1.stories.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Run with -DargLine=-Xmx64m to catch retention of the 2.4 million pair scores. */
class StoryPartitionMemoryTests {
    @Test
    void boundsRetainedPairsInDenseIncrementalBacklog() {
        runDenseBacklog(2_200, 16, 2_200, 1);
    }

    @Test
    @EnabledIfSystemProperty(named = "stories.load-test", matches = "true")
    void processesThreeProductionSizedDenseBacklogs() {
        runDenseBacklog(50_175, 1_536, 965, 3);
    }

    private void runDenseBacklog(int size, int dimension, int articlesPerWindow, int runs) {
        var inputs = new ArrayList<StorySnapshotRepository.SnapshotInput>(size);
        for (int index = 0; index < size; index++) {
            String ref = Integer.toString(size + index);
            float[] vector = new float[dimension];
            vector[index % 4] = 1;
            vector[dimension - 1] = 4;
            inputs.add(new StorySnapshotRepository.SnapshotInput(index, ref, ref,
                    Instant.EPOCH.plus(Duration.ofDays(2L * (index / articlesPerWindow))),
                    "PUBLISHED_AT", null, ref, index, ref, null, dimension, vector));
        }
        var refs = inputs.stream().map(StorySnapshotRepository.SnapshotInput::articleRef)
                .collect(Collectors.toSet());
        var rules = new StoryPartitionService.SnapshotRules(1, "snapshot", "hash", "version",
                dimension, 24, new BigDecimal("0.700000"), "exact-cosine-radius-v1",
                StoryPartitionService.COMPONENT_RULE);
        var version = new StorySnapshotRepository.ClusteringVersion(1, "version", dimension, 24,
                rules.threshold(), rules.searchMode(), "pair-rule");
        int groups = (size + articlesPerWindow - 1) / articlesPerWindow;
        int lastWindow = size - (groups - 1) * articlesPerWindow;
        long compared = (long) (groups - 1) * articlesPerWindow * (articlesPerWindow - 1)
                + (long) lastWindow * (lastWindow - 1);

        for (int run = 0; run < runs; run++) {
            var search = new StoryIncrementalPairSearch(version, inputs);
            var partition = StoryPartitionService.incrementalPartition(rules, inputs, search, refs, List.of());
            var result = search.result();
            assertThat(partition.components()).hasSize(groups);
            assertThat(result.comparedPairs()).isEqualTo(compared);
            assertThat(result.decisions()).hasSizeLessThanOrEqualTo(2 * size);
        }
    }

    @Test
    void partitionsLargeSparseSnapshotWithoutRetainingEveryPair() {
        int size = 2200;
        int dimension = 64;
        var inputs = new ArrayList<StorySnapshotRepository.SnapshotInput>();
        Random random = new Random(38);
        for (int i = 0; i < size; i++) {
            ByteBuffer buffer = ByteBuffer.allocate(dimension * Float.BYTES);
            for (int j = 0; j < dimension; j++) buffer.putFloat(random.nextBoolean() ? 1 : -1);
            byte[] bytes = buffer.array();
            String ref = "%064d".formatted(i);
            inputs.add(new StorySnapshotRepository.SnapshotInput(i, ref, ref, Instant.EPOCH,
                    "PUBLISHED_AT", ref, i, StorySnapshotCanonicalizer.sha256(bytes), bytes, dimension));
        }
        var rules = new StoryPartitionService.SnapshotRules(1, "snapshot", "hash", "version",
                dimension, 24, new BigDecimal("0.700000"), "exact-cosine-radius-v1",
                StoryPartitionService.COMPONENT_RULE);
        var result = StoryPartitionService.partition(rules, inputs, false);
        assertThat(result.components()).hasSize(size);
        assertThat(result.decisions()).isEmpty();
        assertThat(result.components()).allSatisfy(component -> assertThat(component.members()).hasSize(1));
        var version = new StorySnapshotRepository.ClusteringVersion(1, "version", dimension, 24,
                new BigDecimal("0.700000"), "exact-cosine-radius-v1", "pair-rule");
        var search = StorySnapshotService.search(version, inputs);
        assertThat(search.comparedPairs()).isEqualTo((long) size * (size - 1) / 2);
        assertThat(StoryPartitionService.partitionForPublication(rules, inputs, search.decisions()).components())
                .isEqualTo(result.components());
    }
}
