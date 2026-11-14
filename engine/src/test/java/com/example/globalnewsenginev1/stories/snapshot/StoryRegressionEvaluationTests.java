package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StoryRegressionEvaluationTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void verifiesDispositionReasonAndMissingMembershipForExcludedInputs() throws Exception {
        var corpus = json.readTree("""
                {
                  "corpusVersion": "regression-v2",
                  "relationshipCases": [],
                  "inputQualityCases": [
                    {"id":"correct","articleRef":"correct","expectedDisposition":"EXCLUDE",
                     "expectedExclusionReason":"NON_EVENT"},
                    {"id":"wrong-reason","articleRef":"wrong-reason","expectedDisposition":"EXCLUDE",
                     "expectedExclusionReason":"NON_EVENT"},
                    {"id":"has-membership","articleRef":"has-membership","expectedDisposition":"EXCLUDE",
                     "expectedExclusionReason":"NON_EVENT"},
                    {"id":"included","articleRef":"included","expectedDisposition":"INCLUDE"}
                  ]
                }
                """);
        var decisions = Map.of(
                "correct", new StoryReleaseEvaluation.InputDecision("EXCLUDE", "NON_EVENT"),
                "wrong-reason", new StoryReleaseEvaluation.InputDecision("EXCLUDE", "OTHER"),
                "has-membership", new StoryReleaseEvaluation.InputDecision("EXCLUDE", "NON_EVENT"),
                "included", new StoryReleaseEvaluation.InputDecision("INCLUDE", null));
        var assignments = Map.of(
                "has-membership", UUID.randomUUID(),
                "included", UUID.randomUUID());

        var result = StoryRegressionEvaluation.evaluate(corpus, assignments, decisions);

        assertThat(result.inputQualityDeviations())
                .containsExactly("wrong-reason", "has-membership");
        assertThat(result.totalDeviations()).isEqualTo(2);
        assertThat(result).isEqualTo(StoryRegressionEvaluation.evaluate(corpus, assignments, decisions));
    }
}
