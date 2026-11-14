package com.example.globalnewsenginev1.stories.embedding;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class StoryInputEligibilityRule {

    static final String INCLUDE_ALL_VERSION = "all-articles-v1";
    static final String NAVIGATION_SERVICE_VERSION = "navigation-service-exact-path-title-v1";
    static final String NON_EVENT_CONTENT_VERSION = "non-event-content-v1";
    static final String NAVIGATION_SERVICE_REASON = "NAVIGATION_SERVICE";
    static final String CORPORATE_RELEASE_REASON = "CORPORATE_RELEASE";
    static final String ADVICE_REASON = "ADVICE";
    static final String GUIDE_LISTICLE_REASON = "GUIDE_LISTICLE";
    static final String POLL_VOTE_REASON = "POLL_VOTE";
    static final String TOPIC_LANDING_REASON = "TOPIC_LANDING";

    private static final Pattern CORPORATE_RELEASE_PATH = Pattern.compile(
            "^/(?:[^/]+/)*(?:press|news|media)-releases?/[^/]+(?:/[^/]+)*/?$");
    private static final Pattern TRANSACTION_IN_OWN_SHARES = Pattern.compile(
            "^transaction in own shares(?:\\s+(?:\\d.*|[-–—|:]\\s*\\S.*))?$");
    private static final Pattern GUIDE_LISTICLE_TITLE = Pattern.compile(
            "^\\d+ (?:best|ways|tips|reasons)\\b.*$");
    private static final Pattern TOPIC_LANDING_PATH = Pattern.compile("^/topics?/[^/]+/?$");

    private static final Map<String, String> NAVIGATION_TITLES = Map.of(
            "/about", "about",
            "/advertise", "advertise",
            "/meet-the-team", "meet the team",
            "/privacy", "privacy policy",
            "/resources", "resources",
            "/support", "support");

    private StoryInputEligibilityRule() {
    }

    static Decision evaluate(String ruleVersion, String canonicalUrl, String normalizedTitle) {
        return evaluate(ruleVersion, canonicalUrl, normalizedTitle, false);
    }

    public static Decision evaluate(String ruleVersion, String canonicalUrl, String normalizedTitle,
                                    boolean hasEventSignal) {
        if (INCLUDE_ALL_VERSION.equals(ruleVersion)) {
            return Decision.INCLUDE;
        }
        if (!NAVIGATION_SERVICE_VERSION.equals(ruleVersion)
                && !NON_EVENT_CONTENT_VERSION.equals(ruleVersion)) {
            throw new IllegalArgumentException("Unsupported story input eligibility rule: " + ruleVersion);
        }
        if (normalizedTitle == null) {
            return Decision.INCLUDE;
        }
        try {
            String path = URI.create(canonicalUrl).getPath().toLowerCase(Locale.ROOT);
            String expectedTitle = NAVIGATION_TITLES.get(path);
            String title = normalizedTitle.toLowerCase(Locale.ROOT);
            if (expectedTitle != null
                    && (title.equals(expectedTitle) || title.startsWith(expectedTitle + " "))) {
                return new Decision("EXCLUDE", NAVIGATION_SERVICE_REASON);
            }
            if (NAVIGATION_SERVICE_VERSION.equals(ruleVersion) || hasEventSignal) {
                return Decision.INCLUDE;
            }
            if (CORPORATE_RELEASE_PATH.matcher(path).matches()
                    && TRANSACTION_IN_OWN_SHARES.matcher(title).matches()) {
                return new Decision("EXCLUDE", CORPORATE_RELEASE_REASON);
            }
            if (title.startsWith("how to ")) {
                return new Decision("EXCLUDE", ADVICE_REASON);
            }
            if (GUIDE_LISTICLE_TITLE.matcher(title).matches()) {
                return new Decision("EXCLUDE", GUIDE_LISTICLE_REASON);
            }
            if (title.startsWith("poll:") || title.startsWith("vote:")) {
                return new Decision("EXCLUDE", POLL_VOTE_REASON);
            }
            if (TOPIC_LANDING_PATH.matcher(path).matches()) {
                return new Decision("EXCLUDE", TOPIC_LANDING_REASON);
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid canonical URLs stay included conservatively.
        }
        return Decision.INCLUDE;
    }

    public record Decision(String disposition, String reason) {
        private static final Decision INCLUDE = new Decision("INCLUDE", null);

        boolean excluded() {
            return "EXCLUDE".equals(disposition);
        }
    }
}
