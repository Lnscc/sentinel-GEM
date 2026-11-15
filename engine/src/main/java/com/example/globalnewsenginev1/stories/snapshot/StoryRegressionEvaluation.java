package com.example.globalnewsenginev1.stories.snapshot;

import com.example.globalnewsenginev1.stories.embedding.StoryInputEligibilityRule;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
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
        fillMissingInputDecisions(versionId, corpus, dispositions);

        Result base = evaluate(corpus, assignments, dispositions);
        JsonNode componentCorpus = corpus.path("componentRegression");
        if (!componentCorpus.isObject()) return base;
        String componentRule = jdbc.queryForObject("""
                SELECT component_rule_version FROM story_clustering_versions WHERE id = ?
                """, String.class, versionId);
        ComponentResult component = evaluateComponentRegression(componentCorpus, componentRule);
        return new Result(base.corpusVersion(), base.corpusHash(), base.relationshipCases(),
                base.uncertainExcluded(), base.relationshipDeviations(), base.inputQualityCases(),
                base.inputQualityDeviations(), component.relationshipCases(), component.deviations(),
                base.totalDeviations() + component.deviations().size());
    }

    private void fillMissingInputDecisions(long versionId, JsonNode corpus,
                                           Map<String, StoryReleaseEvaluation.InputDecision> dispositions) {
        Map<String, JsonNode> articles = new HashMap<>();
        corpus.path("articles").forEach(article -> articles.put(article.path("ref").asText(), article));
        if (!corpus.path("inputQualityCases").isArray()) return;
        String ruleVersion = jdbc.queryForObject("""
                SELECT input_eligibility_rule_version FROM story_clustering_versions WHERE id = ?
                """, String.class, versionId);
        for (JsonNode testCase : corpus.path("inputQualityCases")) {
            String articleRef = required(testCase, "articleRef");
            if (dispositions.containsKey(articleRef)) continue;
            JsonNode article = articles.get(articleRef);
            if (article == null) continue;
            var decision = StoryInputEligibilityRule.evaluate(ruleVersion,
                    required(article, "canonicalUrl"), required(article, "title"),
                    article.path("hasEventSignal").asBoolean(false));
            dispositions.put(articleRef, new StoryReleaseEvaluation.InputDecision(
                    decision.disposition(), decision.reason()));
        }
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
                0, List.of(), relationshipDeviations.size() + inputDeviations.size());
    }

    static ComponentResult evaluateComponentRegression(JsonNode corpus, String componentRule) {
        JsonNode splits = corpus.path("splits");
        if (!splits.isObject()) {
            throw new IllegalArgumentException("Component regression corpus is missing splits");
        }
        int windowHours = corpus.path("candidateWindowHours").asInt();
        BigDecimal threshold = new BigDecimal(required(corpus, "candidateSimilarityThreshold"));
        String searchMode = required(corpus, "candidateSearchMode");
        List<String> deviations = new ArrayList<>();
        int relationships = 0;
        var splitIterator = splits.fields();
        while (splitIterator.hasNext()) {
            var split = splitIterator.next();
            if (!split.getValue().isArray()) {
                throw new IllegalArgumentException("Component regression split must be an array: "
                        + split.getKey());
            }
            for (JsonNode testCase : split.getValue()) {
                List<StorySnapshotRepository.SnapshotInput> inputs = new ArrayList<>();
                List<String> expectedStories = new ArrayList<>();
                int id = 1;
                for (JsonNode article : testCase.path("articles")) {
                    String ref = required(article, "ref");
                    byte[] vector = ByteBuffer.allocate(8)
                            .putFloat((float) Math.cos(Math.toRadians(article.path("degrees").asDouble())))
                            .putFloat((float) Math.sin(Math.toRadians(article.path("degrees").asDouble())))
                            .array();
                    inputs.add(new StorySnapshotRepository.SnapshotInput(id, ref, "fingerprint-" + ref,
                            Instant.EPOCH, "PUBLISHED_AT", required(article, "title"), "title-" + ref,
                            id, StorySnapshotCanonicalizer.sha256(vector), vector, 2));
                    expectedStories.add(required(article, "story"));
                    id++;
                }
                StoryPartitionService.SnapshotRules rules = new StoryPartitionService.SnapshotRules(
                        1, "component-regression", "component-regression", required(corpus, "corpusVersion"),
                        2, windowHours, threshold, searchMode, componentRule);
                StoryPartitionService.Partition partition = StoryPartitionService.partition(rules, inputs, false);
                Map<String, Integer> actualGroups = new HashMap<>();
                for (int group = 0; group < partition.components().size(); group++) {
                    for (var member : partition.components().get(group).members()) {
                        actualGroups.put(member.articleRef(), group);
                    }
                }
                for (int left = 0; left < inputs.size(); left++) {
                    for (int right = left + 1; right < inputs.size(); right++) {
                        relationships++;
                        boolean expectedSame = expectedStories.get(left).equals(expectedStories.get(right));
                        boolean actualSame = actualGroups.get(inputs.get(left).articleRef())
                                .equals(actualGroups.get(inputs.get(right).articleRef()));
                        if (expectedSame != actualSame) {
                            deviations.add(required(testCase, "id") + ":"
                                    + inputs.get(left).articleRef() + ":" + inputs.get(right).articleRef());
                        }
                    }
                }
            }
        }
        return new ComponentResult(relationships, List.copyOf(deviations));
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException("Regression corpus is missing " + field);
        return value;
    }

    public record Result(String corpusVersion, String corpusHash,
                         int relationshipCases, int uncertainExcluded,
                         List<String> relationshipDeviations, int inputQualityCases,
                         List<String> inputQualityDeviations, int componentRelationshipCases,
                         List<String> componentDeviations, int totalDeviations) {
        public Result {
            relationshipDeviations = relationshipDeviations == null ? List.of()
                    : List.copyOf(relationshipDeviations);
            inputQualityDeviations = inputQualityDeviations == null ? List.of()
                    : List.copyOf(inputQualityDeviations);
            componentDeviations = componentDeviations == null ? List.of()
                    : List.copyOf(componentDeviations);
        }
    }

    record ComponentResult(int relationshipCases, List<String> deviations) { }
}
