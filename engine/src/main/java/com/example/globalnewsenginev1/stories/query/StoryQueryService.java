package com.example.globalnewsenginev1.stories.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    private static final String STORY_ARTICLE_COLUMNS = """
            story.representative_article_id AS article_id,
            story.representative_canonical_url AS canonical_url,
            story.representative_domain AS domain,
            story.representative_first_seen_at AS first_seen_at,
            story.representative_title AS title,
            story.representative_published_at AS published_at,
            story.representative_main_image_url AS main_image_url
            """;

    private static final String VISIBLE_STORY = """
            FROM stories story
            JOIN story_clustering_versions version
              ON version.id = story.clustering_version_id AND version.version_key = ?
            LEFT JOIN story_locations primary_location
              ON primary_location.story_id = story.id AND primary_location.rank = 1
            WHERE story.state <> 'SUPERSEDED'
            """;

    private static final String STORY_SELECT = """
            SELECT story.public_id, story.state, story.effective_from, story.effective_to,
                   primary_location.name AS location_name,
                   primary_location.country_code, primary_location.region_code,
                   primary_location.latitude, primary_location.longitude,
                   primary_location.source, primary_location.confidence,
                   primary_location.supporting_articles, primary_location.supporting_domains,
            """ + STORY_ARTICLE_COLUMNS + """
            , story.member_count
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
            case "articleCount" -> "story.member_count";
            default -> throw new IllegalArgumentException("sort must be time or articleCount");
        };
        String sortDirection = switch (direction) {
            case "asc" -> "ASC";
            case "desc" -> "DESC";
            default -> throw new IllegalArgumentException("direction must be asc or desc");
        };
        String order = "\nORDER BY " + sortColumn + " " + sortDirection + ", story.public_id ASC";
        List<Object> filterParameters = new ArrayList<>();
        filterParameters.add(visibleVersion);
        String filters = filterClause(criteria, filterParameters);
        List<Object> pageParameters = new ArrayList<>(filterParameters);
        pageParameters.add(limit);
        pageParameters.add(offset);
        List<StorySummary> stories = jdbcTemplate.query(STORY_SELECT + filters + order + "\nLIMIT ? OFFSET ?",
                (resultSet, rowNum) -> summary(resultSet), pageParameters.toArray());
        stories = withLocations(stories);
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + VISIBLE_STORY + filters, Long.class, filterParameters.toArray());
        return new StoryPage(stories, offset, limit, total);
    }

    private List<StorySummary> withLocations(List<StorySummary> stories) {
        Map<UUID, StoryLocations> locations = locations(stories.stream().map(StorySummary::id).toList());
        return stories.stream().map(story -> new StorySummary(
                story.id(), story.state(), story.effectiveFrom(), story.effectiveTo(),
                locations.getOrDefault(story.id(), StoryLocations.EMPTY).primary(),
                locations.getOrDefault(story.id(), StoryLocations.EMPTY).all(),
                story.representativeArticle(), story.memberCount())).toList();
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
        if (criteria.country() != null) {
            filters.append(" AND primary_location.country_code = ?");
            parameters.add(criteria.country());
        }
        if (criteria.region() != null) {
            filters.append(" AND primary_location.region_code = ?");
            parameters.add(criteria.region());
        }
        return filters.toString();
    }

    private void validateCriteria(StorySearchCriteria criteria) {
        if (criteria == null) throw new IllegalArgumentException("search criteria must not be null");
        if (criteria.from() != null && criteria.to() != null && !criteria.from().isBefore(criteria.to())) {
            throw new IllegalArgumentException("from must be before to");
        }
        validateLocationCode("country", criteria.country());
        validateLocationCode("region", criteria.region());
    }

    private void validateLocationCode(String parameter, String value) {
        if (value != null && !value.matches("[A-Za-z0-9]{1,32}")) {
            throw new IllegalArgumentException(parameter + " must contain 1 to 32 letters or digits");
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
        StoryLocations locations = locations(List.of(id)).getOrDefault(id, StoryLocations.EMPTY);
        List<StoryMember> members = jdbcTemplate.query(MEMBER_SELECT, (resultSet, rowNum) -> new StoryMember(
                        article(resultSet), resultSet.getString("assignment_reason")), visibleVersion, id);
        return Optional.of(new StoryDetail(
                story.id(), story.state(), story.effectiveFrom(), story.effectiveTo(),
                locations.primary(), locations.all(), story.representativeArticle(), members));
    }

    private StorySummary summary(ResultSet resultSet) throws SQLException {
        return new StorySummary(
                resultSet.getObject("public_id", UUID.class),
                resultSet.getString("state"),
                instant(resultSet, "effective_from"),
                instant(resultSet, "effective_to"),
                location(resultSet),
                List.of(),
                article(resultSet),
                resultSet.getLong("member_count"));
    }

    private Map<UUID, StoryLocations> locations(List<UUID> storyIds) {
        if (storyIds.isEmpty()) return Map.of();
        String placeholders = String.join(", ", java.util.Collections.nCopies(storyIds.size(), "?"));
        String sql = """
                SELECT story.public_id, location.name AS location_name,
                       location.country_code, location.region_code, location.latitude,
                       location.longitude, location.source, location.confidence,
                       location.supporting_articles, location.supporting_domains
                FROM stories story
                JOIN story_clustering_versions version
                  ON version.id = story.clustering_version_id AND version.version_key = ?
                JOIN story_locations location ON location.story_id = story.id
                WHERE story.state <> 'SUPERSEDED' AND story.public_id IN (%s)
                ORDER BY story.public_id, location.rank
                """.formatted(placeholders);
        List<Object> parameters = new ArrayList<>(List.of(visibleVersion));
        parameters.addAll(storyIds);
        Map<UUID, List<StoryLocation>> locations = new HashMap<>();
        jdbcTemplate.query(sql, resultSet -> {
            UUID storyId = resultSet.getObject("public_id", UUID.class);
            locations.computeIfAbsent(storyId, ignored -> new ArrayList<>()).add(location(resultSet));
        }, parameters.toArray());
        Map<UUID, StoryLocations> selected = new HashMap<>();
        locations.forEach((storyId, values) -> selected.put(storyId, new StoryLocations(values.getFirst(), values)));
        return selected;
    }

    private StoryLocation location(ResultSet resultSet) throws SQLException {
        Double latitude = nullableDouble(resultSet, "latitude");
        if (latitude == null) return null;
        return new StoryLocation(resultSet.getString("location_name"), resultSet.getString("country_code"),
                resultSet.getString("region_code"), latitude, resultSet.getDouble("longitude"),
                resultSet.getString("source"), resultSet.getInt("confidence"),
                resultSet.getInt("supporting_articles"), resultSet.getInt("supporting_domains"));
    }

    private Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private record StoryLocations(StoryLocation primary, List<StoryLocation> all) {
        private static final StoryLocations EMPTY = new StoryLocations(null, List.of());

        private StoryLocations {
            all = List.copyOf(all);
        }
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
