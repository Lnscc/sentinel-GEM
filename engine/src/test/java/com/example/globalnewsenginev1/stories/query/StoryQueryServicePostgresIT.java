package com.example.globalnewsenginev1.stories.query;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StoryQueryServicePostgresIT {

    private DataSource adminDataSource;
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void setUp() {
        adminDataSource = dataSource(null);
        Assumptions.assumeTrue(canConnect(adminDataSource),
                "PostgreSQL is not available for story API integration tests");
        schema = "story_api_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(dataSource(schema));
        jdbc.execute("CREATE TABLE story_clustering_versions (id BIGINT PRIMARY KEY, version_key VARCHAR(128), status VARCHAR(16))");
        jdbc.execute("""
                CREATE TABLE articles (id BIGINT PRIMARY KEY, canonical_url TEXT, url_hash VARCHAR(64),
                    domain TEXT, first_seen_at TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.execute("""
                CREATE TABLE gdelt_gkg (id BIGINT PRIMARY KEY, article_id BIGINT,
                    source_timestamp TIMESTAMP WITH TIME ZONE, page_title TEXT,
                    page_precise_pub_timestamp TIMESTAMP WITH TIME ZONE, main_image_url TEXT,
                    locations JSONB)
                """);
        jdbc.execute("""
                CREATE TABLE gdelt_events (id BIGINT PRIMARY KEY, article_id BIGINT,
                    action_geo_type INTEGER, action_geo_name TEXT, action_geo_country_code VARCHAR(16),
                    action_geo_adm1_code VARCHAR(32), action_geo_latitude DOUBLE PRECISION,
                    action_geo_longitude DOUBLE PRECISION, action_geo_feature_id VARCHAR(255))
                """);
        jdbc.execute("""
                CREATE TABLE stories (id UUID PRIMARY KEY, public_id UUID, clustering_version_id BIGINT,
                    representative_article_ref VARCHAR(64), state VARCHAR(16),
                    effective_from TIMESTAMP WITH TIME ZONE, effective_to TIMESTAMP WITH TIME ZONE,
                    representative_article_id BIGINT, representative_canonical_url TEXT,
                    representative_domain TEXT, representative_first_seen_at TIMESTAMP WITH TIME ZONE,
                    representative_title TEXT, representative_published_at TIMESTAMP WITH TIME ZONE,
                    representative_main_image_url TEXT, member_count INTEGER DEFAULT 0,
                    source_count INTEGER DEFAULT 0, independent_report_count INTEGER DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE story_memberships (story_id UUID, clustering_version_id BIGINT,
                    article_id BIGINT, current_marker SMALLINT, assignment_reason TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE story_locations (story_id UUID, rank INTEGER, name TEXT,
                    country_code VARCHAR(16), region_code VARCHAR(32), latitude DOUBLE PRECISION,
                    longitude DOUBLE PRECISION, source VARCHAR(64), confidence INTEGER,
                    supporting_articles INTEGER, supporting_domains INTEGER)
                """);
    }

    @AfterEach
    void tearDown() {
        if (schema != null) {
            new JdbcTemplate(adminDataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void readsTheConfiguredVersionAndRejectsReplacedIds() {
        UUID reused = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID replaced = UUID.fromString("10000000-0000-0000-0000-000000000002");
        jdbc.update("""
                INSERT INTO story_clustering_versions VALUES
                  (1, 'old-version', 'RETIRED'),
                  (2, 'visible-version', 'ACTIVE'),
                  (3, 'other-version', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO articles VALUES
                  (1, 'https://example.org/old', 'old', 'example.org', ?),
                  (2, 'https://example.org/current', 'current', 'example.org', ?)
                """, time("2026-09-01T10:00:00Z"), time("2026-09-02T10:00:00Z"));
        jdbc.update("""
                INSERT INTO stories (
                  id, public_id, clustering_version_id, representative_article_ref, state,
                  effective_from, effective_to) VALUES
                  (?, ?, 1, 'old', 'ACTIVE', ?, ?),
                  (?, ?, 2, 'current', 'ACTIVE', ?, ?),
                  (?, ?, 2, 'old', 'SUPERSEDED', ?, ?),
                  (?, ?, 3, 'old', 'ACTIVE', ?, ?)
                """,
                UUID.randomUUID(), reused, time("2026-09-01T10:00:00Z"), time("2026-09-01T10:00:00Z"),
                UUID.randomUUID(), reused, time("2026-09-02T10:00:00Z"), time("2026-09-02T10:00:00Z"),
                UUID.randomUUID(), replaced, time("2026-09-01T10:00:00Z"), time("2026-09-01T10:00:00Z"),
                UUID.randomUUID(), UUID.randomUUID(), time("2026-09-03T10:00:00Z"), time("2026-09-03T10:00:00Z"));
        UUID internal = jdbc.queryForObject(
                "SELECT id FROM stories WHERE clustering_version_id = 2 AND public_id = ?", UUID.class, reused);
        jdbc.update("INSERT INTO story_memberships VALUES (?, 2, 1, NULL, 'OLD')", internal);
        jdbc.update("INSERT INTO story_memberships VALUES (?, 2, 2, 1, 'MATCHED_COMPONENT')", internal);
        jdbc.update("""
                UPDATE stories story
                SET representative_article_id = article.id,
                    representative_canonical_url = article.canonical_url,
                    representative_domain = article.domain,
                    representative_first_seen_at = article.first_seen_at,
                    member_count = CASE WHEN story.id = ? THEN 1 ELSE 0 END
                FROM articles article
                WHERE article.url_hash = story.representative_article_ref
                """, internal);

        StoryQueryService service = new StoryQueryService(jdbc, "visible-version");

        assertThat(service.stories(0, 20, "time", "desc").stories())
                .extracting(StorySummary::id).containsExactly(reused);
        StoryDetail detail = service.story(reused).orElseThrow();
        assertThat(detail.representativeArticle().canonicalUrl()).endsWith("/current");
        assertThat(detail.members()).extracting(member -> member.article().id()).containsExactly(2L);
        assertThat(service.story(replaced)).isEmpty();
    }

    @Test
    void searchesBeyondTheFirstHundredStoriesBeforePagination() {
        jdbc.update("INSERT INTO story_clustering_versions VALUES (1, 'visible-version', 'ACTIVE')");
        jdbc.execute("""
                INSERT INTO stories (
                  id, public_id, clustering_version_id, representative_article_ref, state,
                  effective_from, effective_to, representative_article_id,
                  representative_canonical_url, representative_domain, representative_first_seen_at,
                  representative_title, member_count, source_count, independent_report_count)
                SELECT
                  ('10000000-0000-0000-0000-' || LPAD(number::text, 12, '0'))::uuid,
                  ('20000000-0000-0000-0000-' || LPAD(number::text, 12, '0'))::uuid,
                  1, 'story-' || number, 'ACTIVE',
                  TIMESTAMPTZ '2026-09-01T10:00:00Z' + number * INTERVAL '1 second',
                  TIMESTAMPTZ '2026-09-01T11:00:00Z' + number * INTERVAL '1 second',
                  number, 'https://example.org/' || number, 'example.org',
                  TIMESTAMPTZ '2026-09-01T10:00:00Z' + number * INTERVAL '1 second',
                  CASE WHEN number = 121 THEN 'Needle beyond first hundred' ELSE 'Ordinary story ' || number END,
                  1, 1, 1000 - number
                FROM generate_series(1, 121) AS number
                """);
        UUID expected = UUID.fromString("20000000-0000-0000-0000-000000000121");
        StoryQueryService service = new StoryQueryService(jdbc, "visible-version");

        assertThat(service.stories(100, 21, "priority", "desc").stories())
                .extracting(StorySummary::id).endsWith(expected);

        StoryPage filtered = service.stories(0, 20, "priority", "desc", new StorySearchCriteria(
                null, null, "needle beyond first hundred", null, null, null, null));

        assertThat(filtered.total()).isOne();
        assertThat(filtered.stories()).extracting(StorySummary::id).containsExactly(expected);
    }

    @Test
    void exploresLocationlessStoriesAndMatchesOverlappingStoryIntervals() {
        jdbc.update("INSERT INTO story_clustering_versions VALUES (1, 'visible-version', 'ACTIVE'), (2, 'old', 'RETIRED')");
        jdbc.execute("""
                INSERT INTO stories (
                    id, public_id, clustering_version_id, state, effective_from, effective_to,
                    representative_title, representative_domain, member_count)
                SELECT
                    ('10000000-0000-0000-0000-' || LPAD(number::text, 12, '0'))::uuid,
                    ('20000000-0000-0000-0000-' || LPAD(number::text, 12, '0'))::uuid,
                    CASE WHEN number = 123 THEN 2 ELSE 1 END,
                    CASE WHEN number = 124 THEN 'SUPERSEDED' ELSE 'ACTIVE' END,
                    TIMESTAMPTZ '2026-09-01T09:00:00Z',
                    CASE WHEN number = 122 THEN TIMESTAMPTZ '2026-09-01T11:59:59Z'
                         ELSE TIMESTAMPTZ '2026-09-01T12:00:00Z' END,
                    CASE WHEN number = 121 THEN 'Percent %_ report (İstanbul)' ELSE 'Ordinary story' END,
                    'representative.org', 2
                FROM generate_series(1, 124) AS number
                """);
        UUID internal = UUID.fromString("10000000-0000-0000-0000-000000000121");
        UUID publicId = UUID.fromString("20000000-0000-0000-0000-000000000121");
        jdbc.update("INSERT INTO articles VALUES (1, 'https://source.org/a', 'one', 'source.org', ?)",
                time("2026-09-01T10:00:00Z"));
        jdbc.update("INSERT INTO story_memberships VALUES (?, 1, 1, 1, 'CURRENT')", internal);
        jdbc.update("INSERT INTO story_locations (story_id, rank, name, country_code) VALUES (?, 1, 'Berlin', 'GM')",
                internal);

        ExploreQueryService service = explorer();
        var defaultPage = service.stories("24h", null, null, null, 1);
        assertThat(defaultPage.total()).isEqualTo(121);
        assertThat(defaultPage.items()).hasSize(50);
        assertThat(defaultPage.items().getFirst().locationName()).isNull();
        assertThat(defaultPage.availableFrom()).isEqualTo(Instant.parse("2026-09-01T09:00:00Z"));
        var thirdPage = service.stories("24h", null, null, null, 3);
        assertThat(thirdPage.items()).hasSize(21);
        assertThat(thirdPage.items().getLast().id()).isEqualTo(publicId);
        assertThat(service.stories("24h", null, null, null, 4).total()).isEqualTo(121);
        assertThat(service.stories("24h", null, null, null, 4).items()).isEmpty();
        assertThat(service.stories("all", null, null, null, 1).total()).isEqualTo(122);
        assertThat(service.stories("24h", "  PERCENT %_  ", "GM", "source.org", 1).items())
                .extracting(ExploreQueryService.StoryResult::id).containsExactly(publicId);
        assertThat(service.stories("24h", "İSTANBUL", null, null, 1).items())
                .extracting(ExploreQueryService.StoryResult::id).containsExactly(publicId);
        assertThat(service.stories("24h", null, null, "representative.org", 1).total()).isZero();
        assertThat(service.countries("stories")).containsExactly(new StoryCountry("GM", "Deutschland"));
    }

    @Test
    void exploresUnassignedArticlesAndFiltersOnlyTheirRecordedGkgCountries() {
        jdbc.update("INSERT INTO story_clustering_versions VALUES (1, 'visible-version', 'ACTIVE'), (2, 'old', 'RETIRED')");
        jdbc.update("""
                INSERT INTO articles (id, canonical_url, domain, first_seen_at) VALUES
                  (1, 'https://unassigned.org/first', 'unassigned.org', ?),
                  (2, 'https://source.org/second', 'source.org', ?),
                  (3, 'https://source.org/old', 'source.org', ?)
                """, time("2026-09-02T10:00:00Z"), time("2026-09-02T10:00:00Z"), time("2026-08-01T10:00:00Z"));
        jdbc.update("""
                INSERT INTO gdelt_gkg (id, article_id, source_timestamp, page_title, locations) VALUES
                (1, 2, ?, 'Article %_ title', '[{"name":"Berlin","countryCode":"GM"},
                                                 {"name":"Amsterdam","countryCode":"NL"},
                                                 {"name":"Berlin","countryCode":"GM"}]'::jsonb),
                (2, 3, ?, null, '[{"name":"Paris","countryCode":"FR"}]'::jsonb),
                (3, null, ?, null, '[{"name":"London","countryCode":"UK"}]'::jsonb)
                """, time("2026-09-02T10:00:00Z"), time("2026-08-01T10:00:00Z"), time("2026-09-02T10:00:00Z"));
        UUID internal = new UUID(1, 1);
        UUID publicId = new UUID(2, 2);
        UUID oldStory = new UUID(3, 3);
        jdbc.update("""
                INSERT INTO stories (id, public_id, clustering_version_id, state)
                VALUES (?, ?, 1, 'ACTIVE'), (?, ?, 2, 'ACTIVE')
                """, internal, publicId, oldStory, new UUID(4, 4));
        jdbc.update("""
                INSERT INTO story_memberships VALUES (?, 2, 1, 1, 'OLD'), (?, 1, 1, NULL, 'PAST'),
                    (?, 1, 2, 1, 'CURRENT')
                """, oldStory, internal, internal);

        ExploreQueryService service = explorer();
        var page = service.articles("24h", null, null, null, 1);
        assertThat(page.items()).extracting(ExploreQueryService.ArticleResult::id).containsExactly(1L, 2L);
        assertThat(page.items().getFirst().storyId()).isNull();
        assertThat(page.items().getFirst().title()).isNull();
        assertThat(page.items().getFirst().mentionedCountries()).isEmpty();
        assertThat(page.items().getLast().storyId()).isEqualTo(publicId);
        assertThat(page.availableFrom()).isEqualTo(Instant.parse("2026-08-01T10:00:00Z"));
        assertThat(service.articles("24h", "  AMSTERDAM  ", "GM", "source.org", 1).items())
                .extracting(ExploreQueryService.ArticleResult::id).containsExactly(2L);
        assertThat(service.articles("24h", "%_", null, null, 1).items().getFirst().mentionedCountries())
                .extracting(StoryCountry::code).containsExactly("GM", "NL");
        assertThat(service.articles("24h", "unassigned.org/FIRST", null, null, 1).items())
                .extracting(ExploreQueryService.ArticleResult::id).containsExactly(1L);
        assertThat(service.articles("all", null, "FR", null, 1).items())
                .extracting(ExploreQueryService.ArticleResult::id).containsExactly(3L);
        assertThat(service.countries("articles")).extracting(StoryCountry::code).containsExactly("FR", "GM", "NL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "SHADOW", "RETIRED", "UNKNOWN"})
    void explorerOnlyPublishesActiveStoriesAndArticleStoryLinks(String status) {
        jdbc.update("INSERT INTO story_clustering_versions VALUES (1, 'visible-version', ?)",
                "UNKNOWN".equals(status) ? "SHADOW" : status);
        jdbc.update("INSERT INTO articles VALUES (1, 'https://example.org/a', 'a', 'example.org', ?)",
                time("2026-09-02T10:00:00Z"));
        UUID internal = new UUID(1, 1);
        UUID publicId = new UUID(2, 1);
        jdbc.update("""
                INSERT INTO stories (id, public_id, clustering_version_id, state, effective_from, effective_to)
                VALUES (?, ?, 1, 'ACTIVE', ?, ?)
                """, internal, publicId, time("2026-09-02T10:00:00Z"), time("2026-09-02T11:00:00Z"));
        jdbc.update("INSERT INTO story_memberships VALUES (?, 1, 1, 1, 'CURRENT')", internal);
        jdbc.update("INSERT INTO story_locations (story_id, rank, name, country_code) VALUES (?, 1, 'Berlin', 'GM')",
                internal);
        ExploreQueryService service = new ExploreQueryService(jdbc,
                "UNKNOWN".equals(status) ? "missing-version" : "visible-version",
                Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC));

        boolean active = "ACTIVE".equals(status);
        var stories = service.stories("all", null, null, null, 1);
        assertThat(stories.total()).isEqualTo(active ? 1 : 0);
        assertThat(stories.items()).hasSize(active ? 1 : 0);
        assertThat(stories.availableFrom()).isEqualTo(active ? Instant.parse("2026-09-02T10:00:00Z") : null);
        assertThat(stories.availableTo()).isEqualTo(active ? Instant.parse("2026-09-02T11:00:00Z") : null);
        assertThat(service.countries("stories")).hasSize(active ? 1 : 0);
        var articles = service.articles("all", null, null, null, 1);
        assertThat(articles.total()).isOne();
        assertThat(articles.items()).singleElement().satisfies(article ->
                assertThat(article.storyId()).isEqualTo(active ? publicId : null));
    }

    private ExploreQueryService explorer() {
        return new ExploreQueryService(jdbc, "visible-version",
                Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC));
    }

    private OffsetDateTime time(String value) {
        return OffsetDateTime.parse(value);
    }

    private DataSource dataSource(String currentSchema) {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        String url = System.getProperty("it.postgres.jdbc-url", "jdbc:postgresql://localhost:5432/gne");
        if (currentSchema != null) {
            url += (url.contains("?") ? "&" : "?") + "currentSchema=" + currentSchema;
        }
        postgres.setUrl(url);
        postgres.setUser(System.getProperty("it.postgres.username", "gne"));
        postgres.setPassword(System.getProperty("it.postgres.password", "gne"));
        return postgres;
    }

    private boolean canConnect(DataSource candidate) {
        try (var ignored = candidate.getConnection()) {
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }
}
