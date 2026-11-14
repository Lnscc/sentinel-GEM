package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryReleaseEvaluationTests {
    @Test
    void exclusionManifestCoversTheEntireCalibrationCorpus() throws Exception {
        var corpus = new ObjectMapper().readTree(Path.of("../docs/analysis/ART-032-corpus.json").toFile());
        var expected = new HashSet<String>();
        corpus.get("articles").forEach(article -> {
            expected.add(article.get("urlHash").asText());
            expected.add(article.get("titleInputHash").asText());
        });
        assertThat(Files.readAllLines(Path.of("src/main/resources/stories/art032-excluded-hashes.txt")))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void rejectsCalibrationLeaksDuplicatesAndMissingProvenanceAndFailsSmallSamples() throws Exception {
        var first = new ObjectMapper().readTree(Path.of("../docs/analysis/ART-032-corpus.json").toFile()).get("articles").get(0);
        String ref = first.get("urlHash").asText();
        String second = "f".repeat(64);
        var pair = new StoryReleaseEvaluation.Pair(ref, second, true, "reason");
        var assignments = Map.of(ref, UUID.randomUUID(), second, UUID.randomUUID());
        assertThatThrownBy(() -> StoryReleaseEvaluation.evaluate(holdout(List.of(pair)), assignments,
                Map.of(ref, "new-title", second, "other-title"))).hasMessageContaining("ART-032");
        var fresh = new StoryReleaseEvaluation.Pair("a", "b", true, "reason");
        var freshAssignments = Map.of("a", UUID.randomUUID(), "b", UUID.randomUUID());
        assertThatThrownBy(() -> StoryReleaseEvaluation.evaluate(holdout(List.of(fresh)), freshAssignments,
                Map.of("a", first.get("titleInputHash").asText(), "b", "other-title"))).hasMessageContaining("ART-032");
        assertThatThrownBy(() -> StoryReleaseEvaluation.evaluate(holdout(List.of(fresh, fresh)), freshAssignments,
                Map.of("a", "new", "b", "other"))).hasMessageContaining("unique");
        assertThatThrownBy(() -> StoryReleaseEvaluation.evaluate(
                new StoryReleaseEvaluation.Holdout("new-v1", "", "reviewer", "independent", List.of(fresh)),
                freshAssignments, Map.of("a", "new", "b", "other"))).hasMessageContaining("provenance");
        assertThat(StoryReleaseEvaluation.evaluate(holdout(List.of(fresh)), freshAssignments,
                Map.of("a", "new", "b", "other")).passed()).isFalse();
    }

    @Test
    void gatesInputQualityWithoutRequiringAssignmentsForExpectedExclusions() {
        var excluded = new StoryReleaseEvaluation.InputQualityCase(
                "excluded", "excluded-ref", "EXCLUDE", "NON_EVENT", "reason");
        var included = new StoryReleaseEvaluation.InputQualityCase(
                "included", "included-ref", "INCLUDE", null, "reason");
        var decisions = Map.of(
                "excluded-ref", new StoryReleaseEvaluation.InputDecision("EXCLUDE", "NON_EVENT"),
                "included-ref", new StoryReleaseEvaluation.InputDecision("INCLUDE", null));
        var assignments = Map.of("included-ref", UUID.randomUUID());

        var result = StoryReleaseEvaluation.evaluate(
                holdout(List.of(), List.of(excluded, included)),
                assignments, Map.of(), decisions);

        assertThat(result.inputQualityCases()).isEqualTo(2);
        assertThat(result.inputQualityDeviations()).isEmpty();
        assertThat(result.gateVersion()).isEqualTo("story-release-gates-v2");
        assertThat(result).isEqualTo(StoryReleaseEvaluation.evaluate(
                holdout(List.of(), List.of(excluded, included)),
                assignments, Map.of(), decisions));
    }

    @Test
    void reportsMissingEligibleAssignmentsAndExclusionReasonOrMembershipMismatches() {
        var cases = List.of(
                new StoryReleaseEvaluation.InputQualityCase(
                        "missing-include", "missing", "INCLUDE", null, "reason"),
                new StoryReleaseEvaluation.InputQualityCase(
                        "wrong-reason", "reason", "EXCLUDE", "NON_EVENT", "reason"),
                new StoryReleaseEvaluation.InputQualityCase(
                        "has-membership", "member", "EXCLUDE", "NON_EVENT", "reason"));
        var decisions = Map.of(
                "missing", new StoryReleaseEvaluation.InputDecision("INCLUDE", null),
                "reason", new StoryReleaseEvaluation.InputDecision("EXCLUDE", "OTHER"),
                "member", new StoryReleaseEvaluation.InputDecision("EXCLUDE", "NON_EVENT"));

        var result = StoryReleaseEvaluation.evaluate(
                holdout(List.of(), cases), Map.of("member", UUID.randomUUID()), Map.of(), decisions);

        assertThat(result.inputQualityDeviations())
                .containsExactly("missing-include", "wrong-reason", "has-membership");
        assertThat(result.passed()).isFalse();
    }

    @Test
    void keepsPairOnlyHoldoutHashAndJsonCompatible() throws Exception {
        var json = new ObjectMapper();
        var holdout = json.readValue(Path.of("../docs/analysis/ART-048-holdout-v1.json").toFile(),
                StoryReleaseEvaluation.Holdout.class);
        Map<String, UUID> assignments = new HashMap<>();
        Map<String, String> titleHashes = new HashMap<>();
        holdout.pairs().stream().flatMap(pair -> List.of(pair.left(), pair.right()).stream())
                .forEach(ref -> {
                    assignments.put(ref, UUID.nameUUIDFromBytes(ref.getBytes()));
                    titleHashes.put(ref, "fresh-" + ref);
                });

        var result = StoryReleaseEvaluation.evaluate(holdout, assignments, titleHashes);

        assertThat(holdout.inputQualityCases()).isEmpty();
        assertThat(result.holdoutHash())
                .isEqualTo("a5e46c9583c7329ff1b44339175586b0a0483bf05c5d0fd4435508ad856ae626");
        assertThat(result.gateVersion()).isEqualTo("story-release-gates-v1");
        assertThat(json.writeValueAsString(holdout)).doesNotContain("inputQualityCases");
    }

    private StoryReleaseEvaluation.Holdout holdout(List<StoryReleaseEvaluation.Pair> pairs) {
        return new StoryReleaseEvaluation.Holdout("new-v1", "synthetic-test", "reviewer", "independent", pairs);
    }

    private StoryReleaseEvaluation.Holdout holdout(
            List<StoryReleaseEvaluation.Pair> pairs,
            List<StoryReleaseEvaluation.InputQualityCase> inputQualityCases) {
        return new StoryReleaseEvaluation.Holdout(
                "new-v1", "synthetic-test", "reviewer", "independent", pairs, inputQualityCases);
    }
}
