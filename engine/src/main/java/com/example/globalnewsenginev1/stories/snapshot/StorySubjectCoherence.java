package com.example.globalnewsenginev1.stories.snapshot;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class StorySubjectCoherence {
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> TEMPLATE_WORDS = Set.of(
            "a", "an", "and", "at", "for", "from", "in", "of", "on", "the", "to", "with",
            "announce", "announces", "business", "co", "company", "corp", "corporation",
            "earning", "earnings", "financial", "fiscal", "first", "fourth", "inc", "key",
            "limited", "ltd", "plc", "provide", "provides", "quarter", "report", "reports",
            "result", "results", "second", "third",
            "takeaway", "takeaways", "update");
    private static final Set<String> GUIDE_WORDS = Set.of(
            "a", "all", "an", "best", "complete", "find", "for", "get", "guide", "guides",
            "how", "in", "learn", "location", "locations", "of", "on", "the", "tips", "to",
            "where");

    private StorySubjectCoherence() {
    }

    static boolean compatible(String leftTitle, String rightTitle) {
        if (leftTitle == null || rightTitle == null || leftTitle.equalsIgnoreCase(rightTitle)) return true;
        Set<String> left = tokens(leftTitle);
        Set<String> right = tokens(rightTitle);
        if (templateCase(left, right)) {
            return sameSubject(subjectTerms(left), subjectTerms(right));
        }
        if (guideCase(left, right)) {
            return guideSubject(leftTitle).equals(guideSubject(rightTitle));
        }
        return true;
    }

    private static boolean templateCase(Set<String> left, Set<String> right) {
        return bothContain(left, right, "takeaway")
                || bothContain(left, right, "takeaways")
                || (hasQuarter(left) && hasQuarter(right)
                && (bothContain(left, right, "earning") || bothContain(left, right, "result")));
    }

    private static boolean hasQuarter(Set<String> tokens) {
        return tokens.contains("quarter")
                || tokens.stream().anyMatch(token -> token.matches("q[1-4]"));
    }

    private static boolean guideCase(Set<String> left, Set<String> right) {
        return hasGuideMarker(left) && hasGuideMarker(right);
    }

    private static boolean hasGuideMarker(Set<String> tokens) {
        return tokens.contains("guide");
    }

    private static Set<String> guideSubject(String title) {
        int separator = Math.max(title.lastIndexOf(':'), Math.max(title.lastIndexOf('|'),
                Math.max(title.lastIndexOf(" — "), Math.max(title.lastIndexOf(" – "),
                        title.lastIndexOf(" - ")))));
        String focus = separator < 0 ? title : title.substring(separator + 1);
        return without(tokens(focus), GUIDE_WORDS);
    }

    private static boolean bothContain(Set<String> left, Set<String> right, String token) {
        return left.contains(token) && right.contains(token);
    }

    private static boolean sameSubject(Set<String> left, Set<String> right) {
        return !left.isEmpty() && left.equals(right);
    }

    private static Set<String> without(Set<String> tokens, Set<String> excluded) {
        Set<String> result = new HashSet<>(tokens);
        result.removeAll(excluded);
        return result;
    }

    private static Set<String> subjectTerms(Set<String> tokens) {
        Set<String> result = without(tokens, TEMPLATE_WORDS);
        result.removeIf(token -> token.matches("(?:q[1-4]|\\d+)") || token.length() < 2);
        return result;
    }

    private static Set<String> tokens(String title) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>();
        Arrays.stream(TOKEN_SEPARATOR.split(normalized))
                .filter(token -> token.length() > 1)
                .map(StorySubjectCoherence::singular)
                .forEach(tokens::add);
        return tokens;
    }

    private static String singular(String token) {
        return token.length() > 4 && token.endsWith("s") && !token.endsWith("ss")
                && !"news".equals(token) ? token.substring(0, token.length() - 1) : token;
    }
}
