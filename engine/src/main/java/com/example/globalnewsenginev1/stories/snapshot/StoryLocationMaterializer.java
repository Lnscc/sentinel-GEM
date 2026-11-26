package com.example.globalnewsenginev1.stories.snapshot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
class StoryLocationMaterializer {
    private final JdbcTemplate jdbc;

    StoryLocationMaterializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void refresh(long versionId) {
        refresh(versionId, null);
    }

    void refresh(long versionId, Set<UUID> storyIds) {
        if (storyIds != null && storyIds.isEmpty()) return;
        NamedParameterJdbcTemplate scopedJdbc = new NamedParameterJdbcTemplate(jdbc);
        MapSqlParameterSource parameters = new MapSqlParameterSource("versionId", versionId);
        String scope = storyIds == null ? "" : " AND story.id = ANY (:storyIds)";
        if (storyIds != null) parameters.addValue("storyIds", new SqlArrayValue("uuid", storyIds.toArray()));
        Map<UUID, List<Candidate>> candidates = new HashMap<>();
        scopedJdbc.query("""
                SELECT story.id AS story_id, member.article_id, article.domain,
                       event.action_geo_type AS location_type, event.action_geo_name AS location_name,
                       event.action_geo_country_code AS country_code,
                       event.action_geo_adm1_code AS region_code,
                       event.action_geo_latitude AS latitude, event.action_geo_longitude AS longitude,
                       event.action_geo_feature_id AS feature_id,
                       (SELECT MAX(mention.confidence) FROM gdelt_mentions mention
                        WHERE mention.global_event_id = event.global_event_id
                          AND mention.mention_identifier = event.source_url) AS event_confidence
                FROM stories story
                JOIN story_memberships member ON member.story_id = story.id
                  AND member.clustering_version_id = story.clustering_version_id
                  AND member.current_marker = 1
                JOIN articles article ON article.id = member.article_id
                JOIN gdelt_events event ON event.article_id = member.article_id
                WHERE story.clustering_version_id = :versionId AND story.state <> 'SUPERSEDED'
                """ + scope, parameters, (RowCallbackHandler) rs -> candidates.computeIfAbsent(rs.getObject("story_id", UUID.class), ignored -> new ArrayList<>())
                .add(new Candidate(nullableInt(rs.getObject("location_type")), rs.getString("location_name"),
                        rs.getString("country_code"), rs.getString("region_code"),
                        nullableDouble(rs.getObject("latitude")), nullableDouble(rs.getObject("longitude")),
                        rs.getString("feature_id"), rs.getLong("article_id"), rs.getString("domain"),
                        nullableInt(rs.getObject("event_confidence")))));

        scopedJdbc.update("DELETE FROM story_locations WHERE story_id IN "
                + "(SELECT story.id FROM stories story WHERE story.clustering_version_id = :versionId"
                + scope + ")", parameters);
        candidates.forEach((storyId, values) -> {
            List<Ranked> ranked = values.stream().filter(Candidate::valid)
                    .collect(Collectors.groupingBy(value -> new Coordinates(value.latitude(), value.longitude())))
                    .values().stream().map(this::rank)
                    .sorted(Comparator.comparingInt(Ranked::confidence).reversed()
                            .thenComparing(Comparator.comparingInt(Ranked::articleCount).reversed())
                            .thenComparing(Comparator.comparingInt(Ranked::domainCount).reversed())
                            .thenComparing(Ranked::candidate, ORDER)).toList();
            for (int rank = 0; rank < ranked.size(); rank++) {
                Ranked location = ranked.get(rank);
                Candidate value = location.candidate();
                jdbc.update("""
                        INSERT INTO story_locations (story_id, rank, name, country_code, region_code,
                            latitude, longitude, source, confidence, supporting_articles, supporting_domains)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'GDELT_EVENT_ACTION', ?, ?, ?)
                        """, storyId, rank + 1, value.name(), value.countryCode(), value.regionCode(),
                        value.latitude(), value.longitude(), location.confidence(),
                        location.articleCount(), location.domainCount());
            }
        });
    }

    private Ranked rank(List<Candidate> candidates) {
        Map<Long, Candidate> byArticle = candidates.stream().collect(Collectors.toMap(
                Candidate::articleId, value -> value,
                (left, right) -> ORDER.compare(left, right) <= 0 ? left : right));
        Set<String> domains = byArticle.values().stream().map(Candidate::domain).collect(Collectors.toSet());
        int confidence = candidates.stream().mapToInt(Candidate::confidence).max().orElseThrow();
        Candidate representative = candidates.stream().filter(value -> value.confidence() == confidence)
                .min(ORDER).orElseThrow();
        return new Ranked(confidence, byArticle.size(), domains.size(), representative);
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Double nullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static final Comparator<Candidate> ORDER = Comparator.comparingInt(Candidate::specificity).reversed()
            .thenComparing(Candidate::countryCode, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Candidate::regionCode, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Candidate::name, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Candidate::latitude).thenComparing(Candidate::longitude)
            .thenComparing(Candidate::featureId, Comparator.nullsLast(Comparator.naturalOrder()));

    private record Candidate(Integer type, String name, String countryCode, String regionCode,
                             Double latitude, Double longitude, String featureId, long articleId,
                             String domain, Integer confidence) {
        boolean valid() {
            return latitude != null && longitude != null && confidence != null
                    && confidence >= 0 && confidence <= 100
                    && Double.isFinite(latitude) && Double.isFinite(longitude)
                    && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
        }

        int specificity() {
            if (type == null) return 0;
            return switch (type) {
                case 3, 4 -> 3;
                case 2, 5 -> 2;
                case 1 -> 1;
                default -> 0;
            };
        }
    }

    private record Coordinates(double latitude, double longitude) {}
    private record Ranked(int confidence, int articleCount, int domainCount, Candidate candidate) {}
}
