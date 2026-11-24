package com.example.globalnewsenginev1.stories.snapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class StoryIncrementalPairSearch {

    private final StorySnapshotRepository.ClusteringVersion version;
    private final List<StorySnapshotRepository.SnapshotInput> inputs;
    private final Map<String, Vector> vectors = new HashMap<>();
    private final LinkedHashSet<StorySnapshotService.PairDecision> evidence = new LinkedHashSet<>();
    private final Duration window;
    private final long threshold;
    private long comparedPairs;

    StoryIncrementalPairSearch(StorySnapshotRepository.ClusteringVersion version,
                               List<StorySnapshotRepository.SnapshotInput> inputs) {
        this.version = version;
        this.inputs = inputs;
        this.window = Duration.ofHours(version.windowHours());
        this.threshold = version.threshold().movePointRight(6).longValueExact();
    }

    float[] vector(StorySnapshotRepository.SnapshotInput input) {
        return searchVector(input).values();
    }

    private Vector searchVector(StorySnapshotRepository.SnapshotInput input) {
        Vector vector = vectors.get(input.articleRef());
        if (vector == null) {
            if (input.embeddingDimension() != version.dimension()) {
                throw new ExactCosine.InvalidVectorException("DIMENSION_MISMATCH",
                        "Artifact dimension differs from clustering version for " + input.articleRef());
            }
            float[] values = input.decodedVector() != null ? input.decodedVector()
                    : ExactCosine.decode(input.vectorBytes(), version.dimension(), input.vectorHash());
            vector = new Vector(values, ExactCosine.norm(values));
            vectors.put(input.articleRef(), vector);
        }
        return vector;
    }

    StorySnapshotRepository.SnapshotInput findMatch(
            StorySnapshotRepository.SnapshotInput input,
            Predicate<StorySnapshotRepository.SnapshotInput> canJoin
    ) {
        Instant first = input.effectiveAt().minus(window);
        Instant last = input.effectiveAt().plus(window);
        Vector inputVector = searchVector(input);
        Match strongestPositive = null;
        Match bestAssignment = null;
        Match nearestRejected = null;
        for (int index = lowerBound(first); index < inputs.size(); index++) {
            var candidate = inputs.get(index);
            if (candidate.effectiveAt().isAfter(last)) break;
            if (candidate.articleInputId() == input.articleInputId()) continue;
            Vector other = searchVector(candidate);
            double score = ExactCosine.cosine(inputVector.values(), other.values(), inputVector.norm(), other.norm());
            comparedPairs++;
            long rounded = ExactCosine.micros(score);
            if (rounded >= threshold) {
                boolean strongest = better(candidate, rounded, strongestPositive);
                boolean best = better(candidate, rounded, bestAssignment) && canJoin.test(candidate);
                if (strongest || best) {
                    var match = new Match(candidate, score, rounded);
                    if (strongest) strongestPositive = match;
                    if (best) bestAssignment = match;
                }
            } else if (strongestPositive == null && (nearestRejected == null
                    || Double.compare(score, nearestRejected.score()) > 0
                    || (Double.compare(score, nearestRejected.score()) == 0
                    && candidate.articleRef().compareTo(nearestRejected.input().articleRef()) < 0))) {
                nearestRejected = new Match(candidate, score, rounded);
            }
        }
        if (strongestPositive != null) {
            evidence.add(decision(input, strongestPositive, false));
            if (bestAssignment != null && bestAssignment != strongestPositive) {
                evidence.add(decision(input, bestAssignment, false));
            }
        } else if (nearestRejected != null) {
            evidence.add(decision(input, nearestRejected, true));
        }
        return bestAssignment == null ? null : bestAssignment.input();
    }

    StorySnapshotService.PairSearchResult result() {
        return StorySnapshotService.rankDecisions(new ArrayList<>(evidence), comparedPairs, comparedPairs);
    }

    private int lowerBound(Instant effectiveAt) {
        int low = 0;
        int high = inputs.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (inputs.get(middle).effectiveAt().isBefore(effectiveAt)) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static boolean better(StorySnapshotRepository.SnapshotInput input,
                                  long score, Match current) {
        if (current == null) return true;
        int order = Long.compare(score, current.roundedScore());
        return order > 0 || (order == 0 && input.articleRef().compareTo(current.input().articleRef()) < 0);
    }

    private StorySnapshotService.PairDecision decision(
            StorySnapshotRepository.SnapshotInput input, Match match, boolean uncertain
    ) {
        var left = input.articleRef().compareTo(match.input().articleRef()) < 0 ? input : match.input();
        var right = left == input ? match.input() : input;
        BigDecimal similarity = uncertain ? ExactCosine.quantize(match.score())
                : BigDecimal.valueOf(match.roundedScore(), 6);
        return new StorySnapshotService.PairDecision(left, right, similarity,
                Duration.between(left.effectiveAt(), right.effectiveAt()).abs().toSeconds(),
                0, uncertain ? "UNCERTAIN" : "SAME_STORY",
                uncertain ? "top-one-below-threshold-v1" : version.pairRuleVersion(), uncertain);
    }

    private record Match(StorySnapshotRepository.SnapshotInput input, double score, long roundedScore) { }

    private record Vector(float[] values, double norm) { }
}
