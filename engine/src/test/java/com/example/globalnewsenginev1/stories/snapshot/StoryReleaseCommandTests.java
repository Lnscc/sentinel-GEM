package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryReleaseCommandTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void combinesOperationalAndGem87RegressionCorpora() throws Exception {
        var operational = json.readTree("""
                {"corpusVersion":"art045-v1","relationshipCases":[]}
                """);
        var component = json.readTree("""
                {"corpusVersion":"gem87-v1","splits":{"regression":[],"unseen":[]}}
                """);

        var combined = StoryReleaseCommand.combineRegression(operational, component);

        assertThat(combined.path("corpusVersion").asText())
                .isEqualTo("art045-v1+gem87-v1");
        assertThat(combined.path("relationshipCases").isArray()).isTrue();
        assertThat(combined.path("componentRegression").path("splits").isObject()).isTrue();
    }
}
