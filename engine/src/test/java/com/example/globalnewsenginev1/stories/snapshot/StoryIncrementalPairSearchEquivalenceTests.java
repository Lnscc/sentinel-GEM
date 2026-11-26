package com.example.globalnewsenginev1.stories.snapshot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class StoryIncrementalPairSearchEquivalenceTests {
    private static final Instant HISTORICAL = Instant.parse("2020-01-01T00:00:00Z");

    @Test
    void keepsRoundedTiesCompatibleAlternativesAndInclusiveHistoricalWindow() {
        var inputs = List.of(
                input(9, HISTORICAL.minusSeconds(86_401), 1, 0),
                input(3, HISTORICAL.minusSeconds(86_400), 0.8000001f, 0.6f),
                input(2, HISTORICAL.minusSeconds(1), 0.8f, 0.6f),
                input(10, HISTORICAL, 1, 0),
                input(1, HISTORICAL.plusSeconds(1), 0.8000002f, 0.6f),
                input(4, HISTORICAL.plusSeconds(86_400), 0.75f, 0.6614378f),
                input(8, HISTORICAL.plusSeconds(86_401), 1, 0));
        var query = inputs.get(3);
        Predicate<StorySnapshotRepository.SnapshotInput> allowed = candidate -> candidate.articleInputId() >= 3;
        var search = new StoryIncrementalPairSearch(version(2), inputs);
        var reference = new ScalarReference(version(2), inputs);

        assertThat(search.findMatch(query, allowed)).isEqualTo(reference.findMatch(query, allowed))
                .isSameAs(inputs.get(1));
        assertThat(search.result()).isEqualTo(reference.result());
        assertThat(search.result().comparedPairs()).isEqualTo(4);
        assertThat(search.result().decisions()).extracting(decision -> decision.right().articleRef())
                .contains(query.articleRef());
    }

    @Test
    void uncertainEvidenceUsesRawScoreBeforeRoundingAndReferenceTieBreak() {
        var inputs = List.of(
                input(2, HISTORICAL.minusSeconds(2), 0.6f, 0.8f),
                input(1, HISTORICAL.minusSeconds(1), 0.6000001f, 0.8f),
                input(10, HISTORICAL, 1, 0),
                input(3, HISTORICAL.plusSeconds(1), 0.6000001f, 0.8f));
        var search = new StoryIncrementalPairSearch(version(2), inputs);
        var reference = new ScalarReference(version(2), inputs);
        var query = inputs.get(2);

        assertThat(search.findMatch(query, candidate -> true)).isNull();
        assertThat(reference.findMatch(query, candidate -> true)).isNull();
        assertThat(search.result()).isEqualTo(reference.result());
        assertThat(search.result().decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.left()).isSameAs(inputs.get(1));
            assertThat(decision.topOneBelowThreshold()).isTrue();
        });
    }

    @Test
    void denseSearchAndRetriesMatchOriginalScalarArithmeticAcrossDimensions() {
        var random = new Random(127);
        for (int dimension : new int[]{2, 3, 31, 1536, 1537}) {
            var inputs = new ArrayList<StorySnapshotRepository.SnapshotInput>();
            float[][] families = new float[4][dimension];
            for (float[] family : families) {
                for (int coordinate = 0; coordinate < dimension; coordinate++) {
                    family[coordinate] = (float) random.nextGaussian();
                }
            }
            for (int index = 0; index < 72; index++) {
                float[] values = families[index % families.length].clone();
                for (int coordinate = 0; coordinate < dimension; coordinate++) {
                    values[coordinate] += (float) (random.nextGaussian() * 0.02);
                }
                inputs.add(input(72 - index, HISTORICAL.plusSeconds(index * 100L), values));
            }
            var first = new StoryIncrementalPairSearch(version(dimension), inputs);
            var retry = new StoryIncrementalPairSearch(version(dimension), inputs);
            var reference = new ScalarReference(version(dimension), inputs);
            assertThat(first.result()).isEqualTo(reference.result());
            for (int index = 0; index < inputs.size(); index += 7) {
                var query = inputs.get(index);
                Predicate<StorySnapshotRepository.SnapshotInput> allowed = candidate ->
                        candidate.articleInputId() % 3 == 0;
                var expected = reference.findMatch(query, allowed);
                assertThat(first.findMatch(query, allowed)).as("dimension %s, query %s", dimension, index)
                        .isEqualTo(expected);
                assertThat(retry.findMatch(query, allowed)).isEqualTo(expected);
                assertThat(first.result()).isEqualTo(reference.result()).isEqualTo(retry.result());
            }
        }
    }

    @Test
    void cosineRetainsScalarBitsForCancellationAndRaggedDimensions() {
        var random = new Random(128);
        for (int dimension : new int[]{1, 2, 3, 7, 31, 1536, 1537}) {
            for (int sample = 0; sample < 24; sample++) {
                float[] left = new float[dimension];
                float[] right = new float[dimension];
                for (int coordinate = 0; coordinate < dimension; coordinate++) {
                    left[coordinate] = Math.scalb((float) random.nextGaussian(), random.nextInt(-30, 30));
                    right[coordinate] = Math.scalb((float) random.nextGaussian(), random.nextInt(-30, 30));
                }
                double actual = ExactCosine.cosine(left, right, ExactCosine.norm(left), ExactCosine.norm(right));
                assertThat(Double.doubleToLongBits(actual))
                        .as("dimension %s, sample %s", dimension, sample)
                        .isEqualTo(Double.doubleToLongBits(ScalarReference.scalarCosine(left, right)));
            }
        }
    }

    @Test
    void batchCosinesRetainScalarBitsForEveryBatchSizeAndLeaveUnusedSlotsAlone() {
        var random = new Random(129);
        double sentinel = -123.456789;
        for (int dimension : new int[]{1, 2, 3, 4, 1536, 1537}) {
            for (int sample = 0; sample < 16; sample++) {
                float[] left = new float[dimension];
                float[][] right = new float[4][dimension];
                for (int coordinate = 0; coordinate < dimension; coordinate++) {
                    left[coordinate] = sample % 4 == 0 ? Float.MIN_VALUE * (coordinate + 1)
                            : Math.scalb(random.nextFloat() * 2 - 1, random.nextInt(-140, 120));
                    if (left[coordinate] == 0) left[coordinate] = Float.MIN_VALUE;
                    for (int candidate = 0; candidate < right.length; candidate++) {
                        right[candidate][coordinate] = switch (candidate) {
                            case 0 -> left[coordinate];
                            case 1 -> -left[coordinate];
                            case 2 -> coordinate % 2 == 0 ? left[coordinate] : -left[coordinate];
                            default -> Math.scalb(random.nextFloat() * 2 - 1, random.nextInt(-140, 120));
                        };
                    }
                }
                // Avoid a zero norm in the independently generated candidate for dimension one.
                if (right[3][0] == 0) right[3][0] = Float.MIN_VALUE;
                double[] norms = Arrays.stream(right).mapToDouble(ExactCosine::norm).toArray();
                for (int count = 0; count <= 4; count++) {
                    double[] scores = new double[5];
                    Arrays.fill(scores, sentinel);
                    ExactCosine.cosines(left, right, ExactCosine.norm(left), norms, scores, count);
                    for (int candidate = 0; candidate < count; candidate++) {
                        assertThat(Double.doubleToLongBits(scores[candidate]))
                                .as("dimension %s, sample %s, count %s, candidate %s",
                                        dimension, sample, count, candidate)
                                .isEqualTo(Double.doubleToLongBits(
                                        ScalarReference.scalarCosine(left, right[candidate])));
                    }
                    for (int unused = count; unused < scores.length; unused++) {
                        assertThat(scores[unused]).isEqualTo(sentinel);
                    }
                }
            }
        }
    }

    private static StorySnapshotRepository.ClusteringVersion version(int dimension) {
        return new StorySnapshotRepository.ClusteringVersion(1, "equivalence", dimension, 24,
                new BigDecimal("0.700000"), "exact-cosine-radius-v1", "cosine-070-time24-v1");
    }

    private static StorySnapshotRepository.SnapshotInput input(long id, Instant time, float... values) {
        var bytes = ByteBuffer.allocate(values.length * Float.BYTES);
        for (float value : values) bytes.putFloat(value);
        String ref = "%064x".formatted(id);
        return new StorySnapshotRepository.SnapshotInput(id, ref, ref, time, "PUBLISHED_AT",
                "fixed title", ref, id, StorySnapshotCanonicalizer.sha256(bytes.array()),
                bytes.array(), values.length, values);
    }

    // Frozen pre-GEM-127 search rules with independent scalar arithmetic and decimal rounding.
    private static final class ScalarReference {
        private final StorySnapshotRepository.ClusteringVersion version;
        private final List<StorySnapshotRepository.SnapshotInput> inputs;
        private final LinkedHashSet<StorySnapshotService.PairDecision> evidence = new LinkedHashSet<>();
        private long compared;

        private ScalarReference(StorySnapshotRepository.ClusteringVersion version,
                                List<StorySnapshotRepository.SnapshotInput> inputs) {
            this.version = version;
            this.inputs = inputs;
        }

        private StorySnapshotRepository.SnapshotInput findMatch(
                StorySnapshotRepository.SnapshotInput query,
                Predicate<StorySnapshotRepository.SnapshotInput> allowed) {
            var positive = new ArrayList<Match>();
            var negative = new ArrayList<Match>();
            for (var candidate : inputs) {
                if (candidate.articleInputId() == query.articleInputId()
                        || Duration.between(candidate.effectiveAt(), query.effectiveAt()).abs()
                        .compareTo(Duration.ofHours(version.windowHours())) > 0) continue;
                double raw = scalarCosine(query.decodedVector(), candidate.decodedVector());
                compared++;
                long rounded = BigDecimal.valueOf(raw).setScale(6, RoundingMode.HALF_UP)
                        .unscaledValue().longValueExact();
                var match = new Match(candidate, raw, rounded);
                (rounded >= version.threshold().movePointRight(6).longValueExact()
                        ? positive : negative).add(match);
            }
            positive.sort(Comparator.comparingLong(Match::rounded).reversed()
                    .thenComparing(match -> match.input().articleRef()));
            if (!positive.isEmpty()) {
                evidence.add(decision(query, positive.getFirst(), false));
                var best = positive.stream().filter(match -> allowed.test(match.input())).findFirst();
                best.ifPresent(match -> evidence.add(decision(query, match, false)));
                return best.map(Match::input).orElse(null);
            }
            negative.stream().min(Comparator.comparingDouble(Match::raw).reversed()
                    .thenComparing(match -> match.input().articleRef()))
                    .ifPresent(match -> evidence.add(decision(query, match, true)));
            return null;
        }

        private StorySnapshotService.PairDecision decision(StorySnapshotRepository.SnapshotInput query,
                                                           Match match, boolean uncertain) {
            var left = query.articleRef().compareTo(match.input().articleRef()) < 0 ? query : match.input();
            var right = left == query ? match.input() : query;
            return new StorySnapshotService.PairDecision(left, right, BigDecimal.valueOf(match.rounded(), 6),
                    Duration.between(left.effectiveAt(), right.effectiveAt()).abs().toSeconds(), 0,
                    uncertain ? "UNCERTAIN" : "SAME_STORY",
                    uncertain ? "top-one-below-threshold-v1" : version.pairRuleVersion(), uncertain);
        }

        private StorySnapshotService.PairSearchResult result() {
            return StorySnapshotService.rankDecisions(new ArrayList<>(evidence), compared, compared);
        }

        private static double scalarCosine(float[] left, float[] right) {
            double dot = 0;
            double leftNorm = 0;
            double rightNorm = 0;
            for (int coordinate = 0; coordinate < left.length; coordinate++) {
                double a = left[coordinate];
                double b = right[coordinate];
                dot += a * b;
                leftNorm += a * a;
                rightNorm += b * b;
            }
            return Math.max(-1, Math.min(1, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
        }

        private record Match(StorySnapshotRepository.SnapshotInput input, double raw, long rounded) { }
    }
}
