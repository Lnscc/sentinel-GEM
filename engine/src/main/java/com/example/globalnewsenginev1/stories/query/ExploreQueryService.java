package com.example.globalnewsenginev1.stories.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class ExploreQueryService {

    private static final int PAGE_SIZE = 50;

    private static final String GKG_LOCATIONS = """
            FROM gdelt_gkg g
            CROSS JOIN LATERAL jsonb_array_elements(g.locations) AS location(value)
            """;

    private static final String ARTICLE_COUNTRIES = """
            SELECT DISTINCT location.value ->> 'countryCode' AS code
            """ + GKG_LOCATIONS + """
            WHERE g.article_id IS NOT NULL AND NULLIF(TRIM(location.value ->> 'countryCode'), '') IS NOT NULL
            ORDER BY code
            """;

    private static final String ARTICLE_COLUMNS = """
            SELECT article.id, article.canonical_url, article.domain, article.first_seen_at,
                   (SELECT g.page_title FROM gdelt_gkg g
                    WHERE g.article_id = article.id AND NULLIF(TRIM(g.page_title), '') IS NOT NULL
                    ORDER BY g.source_timestamp, g.id LIMIT 1) AS title,
                   (SELECT story.public_id FROM story_memberships member
                    JOIN story_clustering_versions version
                      ON version.id = member.clustering_version_id AND version.version_key = ? AND version.status = 'ACTIVE'
                    JOIN stories story ON story.id = member.story_id
                                      AND story.clustering_version_id = member.clustering_version_id
                    WHERE member.article_id = article.id AND member.current_marker = 1
                      AND story.state <> 'SUPERSEDED') AS story_id,
                   ARRAY(SELECT DISTINCT location.value ->> 'countryCode' AS code
            """ + GKG_LOCATIONS + """
                    WHERE g.article_id = article.id AND NULLIF(TRIM(location.value ->> 'countryCode'), '') IS NOT NULL
                    ORDER BY code) AS country_codes
            """;

    private final JdbcTemplate jdbc;
    private final String visibleVersion;
    private final Clock clock;

    @Autowired
    public ExploreQueryService(JdbcTemplate jdbc, @Value("${stories.visible-version}") String visibleVersion) {
        this(jdbc, visibleVersion, Clock.systemUTC());
    }

    ExploreQueryService(JdbcTemplate jdbc, String visibleVersion, Clock clock) {
        this.jdbc = jdbc;
        this.visibleVersion = visibleVersion;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<StoryResult> stories(String period, String query, String country, String domain, int page) {
        validatePage(page);
        Bounds range = period(period);
        List<Object> parameters = new ArrayList<>(List.of(visibleVersion));
        var criteria = new StorySearchCriteria(range.from(), range.to(), text("q", query), text("domain", domain),
                text("country", country), null, null);
        String from = StoryQueryService.VISIBLE_STORY + StoryQueryService.filterClause(criteria, parameters, true);
        Bounds available = jdbc.queryForObject(
                "SELECT MIN(story.effective_from), MAX(story.effective_to) " + StoryQueryService.VISIBLE_STORY,
                (rs, row) -> bounds(rs), visibleVersion);
        return page(page, from, parameters, """
                SELECT story.public_id, story.representative_title, story.representative_domain,
                       story.effective_from, story.effective_to, story.member_count,
                       primary_location.name AS location_name, primary_location.country_code
                """, List.of(), "story.effective_to DESC, story.public_id ASC", available,
                (rs, row) -> new StoryResult(rs.getObject("public_id", UUID.class),
                        rs.getString("representative_title"), rs.getString("representative_domain"),
                        instant(rs, "effective_from"), instant(rs, "effective_to"), rs.getLong("member_count"),
                        rs.getString("location_name"), rs.getString("country_code")));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ArticleResult> articles(String period, String query, String country, String domain, int page) {
        validatePage(page);
        Bounds range = period(period);
        String source = text("domain", domain);
        String mentionedCountry = text("country", country);
        String search = text("q", query);
        List<Object> parameters = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        if (range.from() != null) {
            predicates.add("article.first_seen_at >= ? AND article.first_seen_at < ?");
            parameters.add(Timestamp.from(range.from()));
            parameters.add(Timestamp.from(range.to()));
        }
        if (source != null) {
            predicates.add("article.domain = ?");
            parameters.add(source);
        }
        if (mentionedCountry != null) {
            predicates.add("EXISTS (SELECT 1 " + GKG_LOCATIONS + """
                    WHERE g.article_id = article.id AND location.value ->> 'countryCode' = ?)
                    """);
            parameters.add(mentionedCountry);
        }
        if (search != null) {
            predicates.add("""
                    (strpos(lower(article.canonical_url), lower(?)) > 0
                     OR strpos(lower(article.domain), lower(?)) > 0
                     OR EXISTS (
                         SELECT 1 FROM gdelt_gkg g
                         WHERE g.article_id = article.id AND strpos(lower(g.page_title), lower(?)) > 0)
                     OR EXISTS (SELECT 1
                    """ + GKG_LOCATIONS + """
                         WHERE g.article_id = article.id
                           AND strpos(lower(location.value ->> 'name'), lower(?)) > 0))
                    """);
            parameters.addAll(List.of(search, search, search, search));
        }
        String from = "FROM articles article";
        if (!predicates.isEmpty()) {
            from += " WHERE " + String.join(" AND ", predicates);
        }
        Bounds available = jdbc.queryForObject("SELECT MIN(first_seen_at), MAX(first_seen_at) FROM articles",
                (rs, row) -> bounds(rs));
        return page(page, from, parameters, ARTICLE_COLUMNS, List.of(visibleVersion),
                "article.first_seen_at DESC, article.id ASC", available,
                (rs, row) -> new ArticleResult(rs.getLong("id"), rs.getString("title"),
                        rs.getString("canonical_url"), rs.getString("domain"), instant(rs, "first_seen_at"),
                        Arrays.stream((String[]) rs.getArray("country_codes").getArray())
                                .map(ExploreQueryService::country).toList(),
                        rs.getObject("story_id", UUID.class)));
    }

    public List<StoryCountry> countries(String view) {
        return switch (view) {
            case "stories" -> jdbc.query(StoryQueryService.STORY_COUNTRIES,
                    (rs, row) -> country(rs.getString(1)), visibleVersion);
            case "articles" -> jdbc.query(ARTICLE_COUNTRIES, (rs, row) -> country(rs.getString(1)));
            default -> throw new IllegalArgumentException("view must be stories or articles");
        };
    }

    private <T> Page<T> page(int page, String from, List<Object> parameters, String columns, List<Object> columnParameters,
                             String order, Bounds available, RowMapper<T> rowMapper) {
        long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, parameters.toArray());
        long offset = ((long) page - 1) * PAGE_SIZE;
        List<T> items = List.of();
        if (offset < total) {
            List<Object> pageParameters = new ArrayList<>(columnParameters);
            pageParameters.addAll(parameters);
            pageParameters.add(PAGE_SIZE);
            pageParameters.add(offset);
            items = jdbc.query(columns + " " + from + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                    rowMapper, pageParameters.toArray());
        }
        return new Page<>(items, total, page, PAGE_SIZE, available.from(), available.to());
    }

    private Bounds period(String period) {
        Duration duration = switch (period) {
            case "24h" -> Duration.ofDays(1);
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            case "all" -> null;
            default -> throw new IllegalArgumentException("period must be 24h, 7d, 30d or all");
        };
        if (duration == null) return new Bounds(null, null);
        Instant now = clock.instant();
        return new Bounds(now.minus(duration), now);
    }

    private static void validatePage(int page) {
        if (page < 1) throw new IllegalArgumentException("page must be positive");
    }

    private static StoryCountry country(String code) {
        return new StoryCountry(code, CountryNames.name(code));
    }

    private static String text(String parameter, String value) {
        if (value != null && value.indexOf(0) >= 0) {
            throw new IllegalArgumentException(parameter + " must not contain a null character");
        }
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Bounds bounds(ResultSet rs) throws SQLException {
        OffsetDateTime from = rs.getObject(1, OffsetDateTime.class);
        OffsetDateTime to = rs.getObject(2, OffsetDateTime.class);
        return new Bounds(from == null ? null : from.toInstant(), to == null ? null : to.toInstant());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record Bounds(Instant from, Instant to) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page<T>(List<T> items, long total, int page, int pageSize, Instant availableFrom, Instant availableTo) {}

    public record StoryResult(UUID id, String title, String domain, Instant startedAt, Instant lastSeenAt,
                              long articleCount, String locationName, String countryCode) {}

    public record ArticleResult(long id, String title, String url, String domain, Instant firstSeenAt,
                                List<StoryCountry> mentionedCountries, UUID storyId) {}
}
