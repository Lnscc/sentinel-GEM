package com.example.globalnewsenginev1.stories.query;

import com.example.globalnewsenginev1.articles.normalization.GkgLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StoryQueryService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final String ARTICLE_COLUMNS = """
            article.id AS article_id,
            article.canonical_url,
            article.domain,
            article.first_seen_at,
            (SELECT g.page_title FROM gdelt_gkg g
             WHERE g.article_id = article.id AND g.page_title IS NOT NULL AND TRIM(g.page_title) <> ''
             ORDER BY g.source_timestamp, g.id LIMIT 1) AS title,
            (SELECT g.page_precise_pub_timestamp FROM gdelt_gkg g
             WHERE g.article_id = article.id AND g.page_precise_pub_timestamp IS NOT NULL
             ORDER BY g.source_timestamp, g.id LIMIT 1) AS published_at,
            (SELECT g.main_image_url FROM gdelt_gkg g
             WHERE g.article_id = article.id AND g.main_image_url IS NOT NULL
             ORDER BY g.source_timestamp, g.id LIMIT 1) AS main_image_url
            """;

    private static final String VISIBLE_STORY = """
            FROM stories story
            JOIN story_clustering_versions version
              ON version.id = story.clustering_version_id AND version.version_key = ?
            JOIN articles article ON article.url_hash = story.representative_article_ref
            WHERE story.state <> 'SUPERSEDED'
            """;

    private static final String STORY_SELECT = """
            SELECT story.public_id, story.state, story.effective_from, story.effective_to,
            """ + ARTICLE_COLUMNS + """
            , (SELECT COUNT(*) FROM story_memberships member
               WHERE member.story_id = story.id
                 AND member.clustering_version_id = story.clustering_version_id
                 AND member.current_marker = 1) AS member_count
            """ + VISIBLE_STORY;

    private static final String MEMBER_SELECT = """
            SELECT
            """ + ARTICLE_COLUMNS + """
            , member.assignment_reason
            FROM story_memberships member
            JOIN stories story
              ON story.id = member.story_id
             AND story.clustering_version_id = member.clustering_version_id
            JOIN story_clustering_versions version
              ON version.id = story.clustering_version_id AND version.version_key = ?
            JOIN articles article ON article.id = member.article_id
            WHERE story.public_id = ? AND story.state <> 'SUPERSEDED'
              AND member.current_marker = 1
            ORDER BY article.first_seen_at ASC, article.id ASC
            """;

    private final JdbcTemplate jdbcTemplate;
    private final String visibleVersion;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StoryQueryService(JdbcTemplate jdbcTemplate,
                             @Value("${stories.visible-version}") String visibleVersion) {
        this.jdbcTemplate = jdbcTemplate;
        this.visibleVersion = visibleVersion;
    }

    public StoryPage stories(int offset, int limit, String sort, String direction) {
        return stories(offset, limit, sort, direction, StorySearchCriteria.defaults());
    }

    public StoryPage stories(int offset, int limit, String sort, String direction,
                             StorySearchCriteria criteria) {
        validatePagination(offset, limit);
        validateCriteria(criteria);
        String sortColumn = switch (sort) {
            case "time" -> "story.effective_to";
            case "articleCount" -> "member_count";
            default -> throw new IllegalArgumentException("sort must be time or articleCount");
        };
        String sortDirection = switch (direction) {
            case "asc" -> "ASC";
            case "desc" -> "DESC";
            default -> throw new IllegalArgumentException("direction must be asc or desc");
        };
        String order = "\nORDER BY " + sortColumn + " " + sortDirection
                + ", story.public_id ASC\nLIMIT ? OFFSET ?";
        List<Object> filterParameters = new ArrayList<>();
        filterParameters.add(visibleVersion);
        String filters = filterClause(criteria, filterParameters);
        List<Object> pageParameters = new ArrayList<>(filterParameters);
        pageParameters.add(limit);
        pageParameters.add(offset);
        List<StorySummary> stories = jdbcTemplate.query(STORY_SELECT + filters + order,
                (resultSet, rowNum) -> summary(resultSet), pageParameters.toArray());
        Map<UUID, StoryLocation> locations = locations(stories.stream().map(StorySummary::id).toList());
        stories = stories.stream().map(story -> new StorySummary(
                story.id(), story.state(), story.effectiveFrom(), story.effectiveTo(),
                locations.get(story.id()), story.representativeArticle(), story.memberCount())).toList();
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + VISIBLE_STORY + filters, Long.class, filterParameters.toArray());
        return new StoryPage(stories, offset, limit, total);
    }

    private String filterClause(StorySearchCriteria criteria, List<Object> parameters) {
        StringBuilder filters = new StringBuilder();
        if (criteria.from() != null) {
            filters.append(" AND story.effective_to >= ?");
            parameters.add(Timestamp.from(criteria.from()));
        }
        if (criteria.to() != null) {
            filters.append(" AND story.effective_from < ?");
            parameters.add(Timestamp.from(criteria.to()));
        }
        if (criteria.domain() != null && !criteria.domain().isBlank()) {
            filters.append("""
                     AND EXISTS (
                       SELECT 1
                       FROM story_memberships filtered_member
                       JOIN articles filtered_article ON filtered_article.id = filtered_member.article_id
                       WHERE filtered_member.story_id = story.id
                         AND filtered_member.clustering_version_id = story.clustering_version_id
                         AND filtered_member.current_marker = 1
                         AND filtered_article.domain = ?)
                    """);
            parameters.add(criteria.domain().trim());
        }
        return filters.toString();
    }

    private void validateCriteria(StorySearchCriteria criteria) {
        if (criteria == null) throw new IllegalArgumentException("search criteria must not be null");
        if (criteria.from() != null && criteria.to() != null && !criteria.from().isBefore(criteria.to())) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    public Optional<StoryDetail> story(UUID id) {
        List<StorySummary> stories = jdbcTemplate.query(STORY_SELECT + """
                  AND story.public_id = ?
                """, (resultSet, rowNum) -> summary(resultSet), visibleVersion, id);
        if (stories.isEmpty()) {
            return Optional.empty();
        }
        StorySummary story = stories.getFirst();
        StoryLocation location = locations(List.of(id)).get(id);
        List<StoryMember> members = jdbcTemplate.query(MEMBER_SELECT, (resultSet, rowNum) -> new StoryMember(
                        article(resultSet), resultSet.getString("assignment_reason")), visibleVersion, id);
        return Optional.of(new StoryDetail(
                story.id(), story.state(), story.effectiveFrom(), story.effectiveTo(),
                location, story.representativeArticle(), members));
    }

    private StorySummary summary(ResultSet resultSet) throws SQLException {
        return new StorySummary(
                resultSet.getObject("public_id", UUID.class),
                resultSet.getString("state"),
                instant(resultSet, "effective_from"),
                instant(resultSet, "effective_to"),
                null,
                article(resultSet),
                resultSet.getLong("member_count"));
    }

    private Map<UUID, StoryLocation> locations(List<UUID> storyIds) {
        if (storyIds.isEmpty()) return Map.of();
        String placeholders = String.join(", ", java.util.Collections.nCopies(storyIds.size(), "?"));
        String sql = """
                WITH visible_members AS (
                    SELECT story.public_id, member.article_id
                    FROM stories story
                    JOIN story_clustering_versions version
                      ON version.id = story.clustering_version_id AND version.version_key = ?
                    JOIN story_memberships member
                      ON member.story_id = story.id
                     AND member.clustering_version_id = story.clustering_version_id
                     AND member.current_marker = 1
                    WHERE story.state <> 'SUPERSEDED' AND story.public_id IN (%s)
                )
                SELECT member.public_id, 'GDELT_EVENT_ACTION' AS location_source,
                       event.action_geo_type AS location_type, event.action_geo_name AS location_name,
                       event.action_geo_country_code AS country_code,
                       event.action_geo_adm1_code AS region_code,
                       event.action_geo_latitude AS latitude, event.action_geo_longitude AS longitude,
                       event.action_geo_feature_id AS feature_id, CAST(NULL AS VARCHAR) AS locations_json
                FROM visible_members member
                JOIN gdelt_events event ON event.article_id = member.article_id
                UNION ALL
                SELECT member.public_id, 'GDELT_GKG_MENTION', NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                       CAST(gkg.locations AS VARCHAR)
                FROM visible_members member
                JOIN gdelt_gkg gkg ON gkg.article_id = member.article_id
                """.formatted(placeholders);
        List<Object> parameters = new ArrayList<>();
        parameters.add(visibleVersion);
        parameters.addAll(storyIds);
        Map<UUID, List<LocationCandidate>> candidates = new HashMap<>();
        jdbcTemplate.query(sql, resultSet -> {
            UUID storyId = resultSet.getObject("public_id", UUID.class);
            List<LocationCandidate> storyCandidates = candidates.computeIfAbsent(storyId,
                    ignored -> new ArrayList<>());
            if ("GDELT_EVENT_ACTION".equals(resultSet.getString("location_source"))) {
                storyCandidates.add(new LocationCandidate(
                        nullableInteger(resultSet, "location_type"), resultSet.getString("location_name"),
                        resultSet.getString("country_code"), resultSet.getString("region_code"),
                        nullableDouble(resultSet, "latitude"), nullableDouble(resultSet, "longitude"),
                        resultSet.getString("feature_id"), "GDELT_EVENT_ACTION"));
            } else {
                for (GkgLocation location : gkgLocations(resultSet.getString("locations_json"))) {
                    storyCandidates.add(new LocationCandidate(
                            location.type(), location.name(), location.countryCode(), location.adm1Code(),
                            location.latitude(), location.longitude(), location.featureId(),
                            "GDELT_GKG_MENTION"));
                }
            }
        }, parameters.toArray());
        Map<UUID, StoryLocation> selected = new HashMap<>();
        candidates.forEach((storyId, storyCandidates) -> {
            StoryLocation location = selectLocation(storyCandidates);
            if (location != null) selected.put(storyId, location);
        });
        return selected;
    }

    private StoryLocation selectLocation(List<LocationCandidate> candidates) {
        List<LocationCandidate> valid = candidates.stream().filter(LocationCandidate::valid).toList();
        if (valid.isEmpty()) return null;
        String source = valid.stream().anyMatch(candidate -> candidate.source().equals("GDELT_EVENT_ACTION"))
                ? "GDELT_EVENT_ACTION" : "GDELT_GKG_MENTION";
        return valid.stream()
                .filter(candidate -> candidate.source().equals(source))
                .collect(Collectors.groupingBy(candidate -> new Coordinates(candidate.latitude(), candidate.longitude())))
                .values().stream()
                .map(group -> new RankedLocation(group.size(), group.stream().min(LOCATION_ORDER).orElseThrow()))
                .min(Comparator.comparingInt(RankedLocation::count).reversed()
                        .thenComparing(RankedLocation::candidate, LOCATION_ORDER))
                .map(RankedLocation::candidate)
                .map(candidate -> new StoryLocation(candidate.name(), candidate.countryCode(),
                        candidate.regionCode(), candidate.latitude(), candidate.longitude(), candidate.source()))
                .orElse(null);
    }

    private List<GkgLocation> gkgLocations(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) node = objectMapper.readTree(node.textValue());
            return objectMapper.convertValue(node, new TypeReference<>() { });
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Stored GKG locations are not valid JSON", exception);
        }
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private static final Comparator<LocationCandidate> LOCATION_ORDER =
            Comparator.comparingInt(LocationCandidate::specificity).reversed()
                    .thenComparing(LocationCandidate::countryCode,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(LocationCandidate::regionCode,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(LocationCandidate::name,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(LocationCandidate::latitude)
                    .thenComparing(LocationCandidate::longitude)
                    .thenComparing(LocationCandidate::featureId,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    private record LocationCandidate(
            Integer type,
            String name,
            String countryCode,
            String regionCode,
            Double latitude,
            Double longitude,
            String featureId,
            String source
    ) {
        boolean valid() {
            return latitude != null && longitude != null
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

    private record Coordinates(double latitude, double longitude) {
    }

    private record RankedLocation(int count, LocationCandidate candidate) {
    }

    private StoryArticle article(ResultSet resultSet) throws SQLException {
        return new StoryArticle(
                resultSet.getLong("article_id"),
                resultSet.getString("canonical_url"),
                resultSet.getString("domain"),
                instant(resultSet, "first_seen_at"),
                resultSet.getString("title"),
                nullableInstant(resultSet, "published_at"),
                resultSet.getString("main_image_url"));
    }

    private java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private java.time.Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private void validatePagination(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
