package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class StoryRegressionEvaluation {
    private final JdbcTemplate jdbc;

    StoryRegressionEvaluation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Result evaluate(long versionId, JsonNode corpus) {
        Map<String, UUID> assignments = new HashMap<>();
        jdbc.query("""
                SELECT member.article_ref, member.story_id
                FROM story_memberships member
                WHERE member.clustering_version_id = ? AND member.current_marker = 1
                """, (resultSet, rowNum) -> Map.entry(
                resultSet.getString(1), resultSet.getObject(2, UUID.class)), versionId)
                .forEach(entry -> assignments.put(entry.getKey(), entry.getValue()));
        Map<String, StoryReleaseEvaluation.InputDecision> dispositions = new HashMap<>();
        jdbc.query("""
                SELECT article_ref, input_disposition, exclusion_reason FROM story_article_inputs
                WHERE clustering_version_id = ? AND current_marker = 1
                """, (resultSet, rowNum) -> Map.entry(
                resultSet.getString(1), new StoryReleaseEvaluation.InputDecision(
                        resultSet.getString(2), resultSet.getString(3))), versionId)
                .forEach(entry -> dispositions.put(entry.getKey(), entry.getValue()));

        return evaluate(corpus, assignments, dispositions);
    }

    static Result evaluate(JsonNode corpus, Map<String, UUID> assignments,
                           Map<String, StoryReleaseEvaluation.InputDecision> dispositions) {
        String corpusVersion = required(corpus, "corpusVersion");
        List<String> relationshipDeviations = new ArrayList<>();
        int relationships = 0;
        int uncertain = 0;
        for (JsonNode testCase : corpus.path("relationshipCases")) {
            String expected = required(testCase, "targetLabel");
            if ("UNCERTAIN".equals(expected)) {
                uncertain++;
                continue;
            }
            relationships++;
            UUID left = assignments.get(required(testCase, "left"));
            UUID right = assignments.get(required(testCase, "right"));
            String actual = left != null && left.equals(right) ? "SAME_STORY" : "DIFFERENT_STORY";
            if (left == null || right == null || !expected.equals(actual)) {
                relationshipDeviations.add(required(testCase, "id"));
            }
        }

        List<String> inputDeviations = new ArrayList<>();
        int inputCases = 0;
        for (JsonNode testCase : corpus.path("inputQualityCases")) {
            inputCases++;
            String expectedDisposition = required(testCase, "expectedDisposition");
            String articleRef = required(testCase, "articleRef");
            String expectedReason = testCase.path("expectedExclusionReason").asText(null);
            StoryReleaseEvaluation.InputDecision actual = dispositions.get(articleRef);
            if (actual == null || !Objects.equals(expectedDisposition, actual.disposition())
                    || expectedReason != null && !Objects.equals(expectedReason, actual.exclusionReason())
                    || "EXCLUDE".equals(expectedDisposition) && assignments.containsKey(articleRef)) {
                inputDeviations.add(required(testCase, "id"));
            }
        }
        return new Result(corpusVersion, StoryReleaseEvaluation.hash(corpus.toString()),
                relationships, uncertain, List.copyOf(relationshipDeviations),
                inputCases, List.copyOf(inputDeviations),
                relationshipDeviations.size() + inputDeviations.size());
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException("Regression corpus is missing " + field);
        return value;
    }

    public record Result(String corpusVersion, String corpusHash,
                         int relationshipCases, int uncertainExcluded,
                         List<String> relationshipDeviations, int inputQualityCases,
                         List<String> inputQualityDeviations, int totalDeviations) { }
}
