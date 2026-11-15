package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void evaluatesFrozenInputCasesWhenOperationalRowsExpired() throws Exception {
        var corpus = json.readTree("""
                {
                  "corpusVersion": "regression-v2",
                  "articles": [
                    {"ref":"about","canonicalUrl":"https://example.com/about","title":"About Example"},
                    {"ref":"report","canonicalUrl":"https://example.com/news/fire","title":"Fire closes road"}
                  ],
                  "relationshipCases": [],
                  "inputQualityCases": [
                    {"id":"about","articleRef":"about","expectedDisposition":"EXCLUDE"},
                    {"id":"report","articleRef":"report","expectedDisposition":"INCLUDE"}
                  ]
                }
                """);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(1L)))
                .thenReturn(java.util.List.of());
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(1L)))
                .thenReturn("navigation-service-exact-path-title-v1");

        var result = new StoryRegressionEvaluation(jdbc).evaluate(1L, corpus);

        assertThat(result.inputQualityDeviations()).isEmpty();
    }

    @Test
    void evaluatesGem87ComponentCasesAsAnAbsoluteReleaseGate() throws Exception {
        var corpus = json.readTree(getClass().getResourceAsStream(
                "/stories/gem87-clustering-quality-v1.json"));

        var coherent = StoryRegressionEvaluation.evaluateComponentRegression(
                corpus, StoryPartitionService.COHERENT_COMPONENT_RULE);
        var baseline = StoryRegressionEvaluation.evaluateComponentRegression(
                corpus, StoryPartitionService.COMPONENT_RULE);

        assertThat(coherent.relationshipCases()).isPositive();
        assertThat(coherent.deviations()).isEmpty();
        assertThat(baseline.deviations()).isNotEmpty();
    }
}
