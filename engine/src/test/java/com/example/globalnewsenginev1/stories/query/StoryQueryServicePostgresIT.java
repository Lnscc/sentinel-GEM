package com.example.globalnewsenginev1.stories.query;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
                  (2, 'visible-version', 'SHADOW'),
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
