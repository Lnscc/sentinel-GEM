package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class StoryClusteringQualityTests {
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-11-14T00:00:00Z");

    @Test
    void passesVersionedRegressionAndUnseenCasesDeterministically() throws Exception {
        JsonNode corpus = new ObjectMapper().readTree(getClass().getResourceAsStream(
                "/stories/gem87-clustering-quality-v1.json"));
        assertThat(corpus.path("corpusVersion").asText())
                .isEqualTo("gem87-clustering-quality-v1.2.0");
        assertThat(corpus.path("componentRuleVersion").asText())
                .isEqualTo(StoryPartitionService.COHERENT_COMPONENT_RULE);

        for (String split : List.of("regression", "unseen")) {
            for (JsonNode testCase : corpus.path("splits").path(split)) {
                List<StorySnapshotRepository.SnapshotInput> inputs = new ArrayList<>();
                Map<String, List<String>> expected = new LinkedHashMap<>();
                int id = 1;
                for (JsonNode article : testCase.path("articles")) {
                    String ref = article.path("ref").asText();
                    inputs.add(input(id++, ref, article.path("title").asText(),
                            article.path("degrees").asDouble()));
                    expected.computeIfAbsent(article.path("story").asText(), ignored -> new ArrayList<>())
                            .add(ref);
                }
                List<List<String>> expectedGroups = expected.values().stream()
                        .map(List::copyOf).toList();
                var first = StoryPartitionService.partition(rules(), inputs);
                assertThat(groups(first)).as(split + ":" + testCase.path("id").asText())
                        .containsExactlyInAnyOrderElementsOf(expectedGroups);
                for (int seed = 0; seed < 25; seed++) {
                    Collections.shuffle(inputs, new Random(seed));
                    assertThat(StoryPartitionService.partition(rules(), inputs)).isEqualTo(first);
                }
            }
        }
    }

    private static StorySnapshotRepository.SnapshotInput input(
            long id, String ref, String title, double degrees) {
        byte[] bytes = ByteBuffer.allocate(8)
                .putFloat((float) Math.cos(Math.toRadians(degrees)))
                .putFloat((float) Math.sin(Math.toRadians(degrees))).array();
        return new StorySnapshotRepository.SnapshotInput(id, ref, "fingerprint-" + ref,
                EFFECTIVE_AT, "PUBLISHED_AT", title, "title-hash-" + ref, id,
                StorySnapshotCanonicalizer.sha256(bytes), bytes, 2);
    }

    private static StoryPartitionService.SnapshotRules rules() {
        return new StoryPartitionService.SnapshotRules(1, "snapshot", "input-hash",
                "gem87-quality", 2, 24, new BigDecimal("0.700000"),
                "exact-cosine-radius-v1", StoryPartitionService.COHERENT_COMPONENT_RULE);
    }

    private static List<List<String>> groups(StoryPartitionService.Partition result) {
        return result.components().stream().map(component -> component.members().stream()
                .map(StoryPartitionService.MemberEvidence::articleRef).toList()).toList();
    }
}
