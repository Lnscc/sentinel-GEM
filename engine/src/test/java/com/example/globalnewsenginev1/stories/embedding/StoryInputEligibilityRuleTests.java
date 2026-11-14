package com.example.globalnewsenginev1.stories.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryInputEligibilityRuleTests {

    @Test
    void matchesTheArt045InputQualityCases() throws Exception {
        JsonNode corpus = new ObjectMapper().readTree(Path.of(
                "../docs/analysis/ART-045-regression-cases-v1.json").toFile());
        Map<String, JsonNode> articles = new HashMap<>();
        corpus.path("articles").forEach(article ->
                articles.put(article.path("ref").asText(), article));

        for (JsonNode testCase : corpus.path("inputQualityCases")) {
            JsonNode article = articles.get(testCase.path("articleRef").asText());
            var decision = StoryInputEligibilityRule.evaluate(
                    StoryInputEligibilityRule.NAVIGATION_SERVICE_VERSION,
                    article.path("canonicalUrl").asText(), article.path("title").asText());

            assertThat(decision.disposition())
                    .as(testCase.path("id").asText())
                    .isEqualTo(testCase.path("expectedDisposition").asText());
            if (decision.excluded()) {
                assertThat(decision.reason())
                        .isEqualTo(StoryInputEligibilityRule.NAVIGATION_SERVICE_REASON);
            }
        }
    }

    @Test
    void doesNotMatchNavigationWordsInsideArticlePathsOrTitlesAlone() {
        assertThat(StoryInputEligibilityRule.evaluate(
                StoryInputEligibilityRule.NAVIGATION_SERVICE_VERSION,
                "https://example.org/news/privacy-policy-changes", "Privacy Policy Changes Today")
                .excluded()).isFalse();
        assertThat(StoryInputEligibilityRule.evaluate(
                StoryInputEligibilityRule.NAVIGATION_SERVICE_VERSION,
                "https://example.org/privacy", "Government Announces Tax Changes")
                .excluded()).isFalse();
    }

    @Test
    void rejectsUnknownRuleVersionsInsteadOfChangingBehaviorSilently() {
        assertThatThrownBy(() -> StoryInputEligibilityRule.evaluate(
                "unknown", "https://example.org/about", "About Example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void excludesOnlyClearNonEventCasesWithoutAnEventSignal() {
        Map<String, String[]> cases = Map.of(
                StoryInputEligibilityRule.CORPORATE_RELEASE_REASON, new String[]{
                        "https://www.brantfordexpositor.ca/press-releases/globe-newswire/transaction-in-own-shares-30",
                        "Transaction in Own Shares"},
                StoryInputEligibilityRule.ADVICE_REASON, new String[]{
                        "https://economictimes.indiatimes.com/news/how-to/how-to-access-your-emails-if-microsoft-outlook-is-down-try-these-alternative-ways-to-stay-connected/articleshow/135810357.cms",
                        "How to access your emails if Microsoft Outlook is down? Try these alternative ways to stay connected"},
                StoryInputEligibilityRule.GUIDE_LISTICLE_REASON, new String[]{
                        "https://www.insidermonkey.com/news/10-best-aerospace-dividend-stocks-to-buy-2-1854396",
                        "10 Best Aerospace Dividend Stocks to Buy"},
                StoryInputEligibilityRule.POLL_VOTE_REASON, new String[]{
                        "https://cnycentral.com/news/local/poll-what-is-the-biggest-issue-facing-voters-in-the-midterm-election",
                        "POLL: What is the biggest issue facing voters in the midterm election?"},
                StoryInputEligibilityRule.TOPIC_LANDING_REASON, new String[]{
                        "https://euobserver.com/topic/protests", "Protests – EUobserver"});

        cases.forEach((reason, input) -> {
            var first = StoryInputEligibilityRule.evaluate(
                    StoryInputEligibilityRule.NON_EVENT_CONTENT_VERSION, input[0], input[1], false);
            var second = StoryInputEligibilityRule.evaluate(
                    StoryInputEligibilityRule.NON_EVENT_CONTENT_VERSION, input[0], input[1], false);

            assertThat(first).isEqualTo(second);
            assertThat(first.reason()).isEqualTo(reason);
        });
        assertThat(StoryInputEligibilityRule.evaluate(
                StoryInputEligibilityRule.NON_EVENT_CONTENT_VERSION,
                "https://euobserver.com/eu-political/question", "VOTE: Should the EU expand?", false)
                .reason()).isEqualTo(StoryInputEligibilityRule.POLL_VOTE_REASON);
    }

    @Test
    void keepsConcreteReportsAndEventLinkedArticlesEligible() {
        String[][] controls = {
                {"https://example.com/news-releases/lactalis-opens-centre",
                        "Lactalis opens new research and development centre"},
                {"https://cnycentral.com/news/local/fire-closes-main-street",
                        "Fire closes Main Street after overnight blaze"},
                {"https://euobserver.com/eu-political/158001",
                        "EU leaders approve new sanctions package"},
                {"https://euobserver.com/topics/europe/election-result",
                        "Election result announced in Europe"},
                {"https://culture.example.com/film/anniversary",
                        "10 Things I Hate About You cast reunites for anniversary"}
        };
        for (String[] control : controls) {
            assertThat(StoryInputEligibilityRule.evaluate(
                    StoryInputEligibilityRule.NON_EVENT_CONTENT_VERSION,
                    control[0], control[1], false).excluded()).isFalse();
        }
        assertThat(StoryInputEligibilityRule.evaluate(
                StoryInputEligibilityRule.NON_EVENT_CONTENT_VERSION,
                "https://cnycentral.com/news/local/question",
                "POLL: Should the city expand transit?", true).excluded()).isFalse();
        assertThat(StoryInputEligibilityRule.evaluate(
                StoryInputEligibilityRule.NAVIGATION_SERVICE_VERSION,
                "https://cnycentral.com/news/local/question",
                "POLL: Should the city expand transit?", false).excluded()).isFalse();
    }
}
