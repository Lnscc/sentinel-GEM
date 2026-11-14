package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Release gates are fixed before evaluating a fresh, independently labelled holdout. */
public final class StoryReleaseEvaluation {
    public static final String GATE_VERSION = "story-release-gates-v1";
    public static final String INPUT_QUALITY_GATE_VERSION = "story-release-gates-v2";
    private static final Set<String> CALIBRATION_HASHES = calibrationHashes();

    private StoryReleaseEvaluation() { }

    public record Pair(String left, String right, boolean sameStory, String rationale) { }
    public record InputQualityCase(String id, String articleRef, String expectedDisposition,
                                   String expectedExclusionReason, String rationale) { }
    public record InputDecision(String disposition, String exclusionReason) { }
    public record Holdout(String version, String provenance, String labelledBy,
                          String independenceStatement, List<Pair> pairs,
                          @JsonInclude(JsonInclude.Include.NON_EMPTY)
                          List<InputQualityCase> inputQualityCases) {
        public Holdout {
            pairs = pairs == null ? List.of() : List.copyOf(pairs);
            inputQualityCases = inputQualityCases == null ? List.of() : List.copyOf(inputQualityCases);
        }

        public Holdout(String version, String provenance, String labelledBy,
                       String independenceStatement, List<Pair> pairs) {
            this(version, provenance, labelledBy, independenceStatement, pairs, List.of());
        }
    }
    public record Result(String gateVersion, String holdoutHash, int positives, int negatives,
                         int truePositives, int falsePositives, int falseNegatives,
                         double precision, double recall,
                         @JsonInclude(JsonInclude.Include.NON_DEFAULT) int inputQualityCases,
                         @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> inputQualityDeviations,
                         boolean passed) {
        public Result {
            inputQualityDeviations = inputQualityDeviations == null
                    ? List.of() : List.copyOf(inputQualityDeviations);
        }

        public Result(String gateVersion, String holdoutHash, int positives, int negatives,
                      int truePositives, int falsePositives, int falseNegatives,
                      double precision, double recall, boolean passed) {
            this(gateVersion, holdoutHash, positives, negatives, truePositives, falsePositives,
                    falseNegatives, precision, recall, 0, List.of(), passed);
        }
    }

    static Result evaluate(Holdout holdout, Map<String, java.util.UUID> assignments,
                           Map<String, String> titleHashes) {
        return evaluate(holdout, assignments, titleHashes, Map.of());
    }

    static Result evaluate(Holdout holdout, Map<String, java.util.UUID> assignments,
                           Map<String, String> titleHashes,
                           Map<String, InputDecision> inputDecisions) {
        requireText(holdout.version(), "Holdout version");
        requireText(holdout.provenance(), "Holdout provenance");
        requireText(holdout.labelledBy(), "Holdout labeller");
        requireText(holdout.independenceStatement(), "Holdout independence statement");
        Set<String> pairs = new HashSet<>();
        int positives = 0, negatives = 0, tp = 0, fp = 0, fn = 0;
        for (Pair pair : holdout.pairs()) {
            requireText(pair.rationale(), "Label rationale");
            if (pair.left() == null || pair.right() == null || pair.left().compareTo(pair.right()) >= 0
                    || !pairs.add(pair.left() + ":" + pair.right())) {
                throw new IllegalArgumentException("Holdout pairs must be unique and ordered by article ref");
            }
            for (String ref : List.of(pair.left(), pair.right())) {
                if (!assignments.containsKey(ref) || !titleHashes.containsKey(ref)) {
                    throw new IllegalArgumentException("Holdout article is absent from the published candidate: " + ref);
                }
                if (CALIBRATION_HASHES.contains(ref) || CALIBRATION_HASHES.contains(titleHashes.get(ref))) {
                    throw new IllegalArgumentException("ART-032 articles/titles cannot serve as release evidence");
                }
            }
            boolean predicted = assignments.get(pair.left()).equals(assignments.get(pair.right()));
            if (pair.sameStory()) {
                positives++;
                if (predicted) tp++; else fn++;
            } else {
                negatives++;
                if (predicted) fp++;
            }
        }
        List<String> inputDeviations = new java.util.ArrayList<>();
        Set<String> inputCaseIds = new HashSet<>();
        for (InputQualityCase testCase : holdout.inputQualityCases()) {
            requireText(testCase.id(), "Input quality case id");
            requireText(testCase.articleRef(), "Input quality article ref");
            requireText(testCase.rationale(), "Input quality rationale");
            if (!inputCaseIds.add(testCase.id())) {
                throw new IllegalArgumentException("Input quality case ids must be unique");
            }
            if (!Set.of("INCLUDE", "EXCLUDE").contains(testCase.expectedDisposition())) {
                throw new IllegalArgumentException("Input quality disposition must be INCLUDE or EXCLUDE");
            }
            String titleHash = titleHashes.get(testCase.articleRef());
            if (CALIBRATION_HASHES.contains(testCase.articleRef())
                    || titleHash != null && CALIBRATION_HASHES.contains(titleHash)) {
                throw new IllegalArgumentException("ART-032 articles/titles cannot serve as release evidence");
            }
            InputDecision actual = inputDecisions.get(testCase.articleRef());
            boolean deviates = actual == null
                    || !testCase.expectedDisposition().equals(actual.disposition())
                    || testCase.expectedExclusionReason() != null
                    && !testCase.expectedExclusionReason().equals(actual.exclusionReason())
                    || "EXCLUDE".equals(testCase.expectedDisposition())
                    && assignments.containsKey(testCase.articleRef())
                    || "INCLUDE".equals(testCase.expectedDisposition())
                    && !assignments.containsKey(testCase.articleRef());
            if (deviates) inputDeviations.add(testCase.id());
        }
        double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double recall = positives == 0 ? 0 : (double) tp / positives;
        String gateVersion = holdout.inputQualityCases().isEmpty()
                ? GATE_VERSION : INPUT_QUALITY_GATE_VERSION;
        return new Result(gateVersion, holdoutHash(holdout), positives, negatives, tp, fp, fn,
                precision, recall, holdout.inputQualityCases().size(), inputDeviations,
                positives >= 20 && negatives >= 20 && precision >= 0.95 && recall >= 0.90
                        && inputDeviations.isEmpty());
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    static String hash(String value) {
        return StorySnapshotCanonicalizer.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String holdoutHash(Holdout holdout) {
        try {
            return StorySnapshotCanonicalizer.sha256(new ObjectMapper().writeValueAsBytes(holdout));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize holdout", exception);
        }
    }

    private static Set<String> calibrationHashes() {
        try (var input = StoryReleaseEvaluation.class.getResourceAsStream("/stories/art032-excluded-hashes.txt")) {
            if (input == null) throw new IllegalStateException("Missing ART-032 exclusion manifest");
            return Set.copyOf(new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read ART-032 exclusion manifest", exception);
        }
    }
}
