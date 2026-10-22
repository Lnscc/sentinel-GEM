package com.example.globalnewsenginev1.stories.query;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StoryQueryServiceTests {

    private JdbcTemplate jdbc;
    private StoryQueryService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:story-queries-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE story_clustering_versions (id BIGINT PRIMARY KEY, version_key VARCHAR(128), status VARCHAR(16))");
        jdbc.execute("""
                CREATE TABLE articles (id BIGINT PRIMARY KEY, canonical_url TEXT, url_hash VARCHAR(64),
                    domain TEXT, first_seen_at TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.execute("""
                CREATE TABLE gdelt_gkg (id BIGINT PRIMARY KEY, article_id BIGINT,
                    source_timestamp TIMESTAMP WITH TIME ZONE, page_title TEXT,
                    page_precise_pub_timestamp TIMESTAMP WITH TIME ZONE, main_image_url TEXT,
                    locations JSON)
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
                    effective_from TIMESTAMP WITH TIME ZONE, effective_to TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.execute("""
                CREATE TABLE story_memberships (story_id UUID, clustering_version_id BIGINT,
                    article_id BIGINT, current_marker SMALLINT, assignment_reason TEXT)
                """);
        service = new StoryQueryService(jdbc, "visible-version");
    }

    @Test
    void returnsOnlyCurrentVisibleStoriesWithStablePagination() {
        seedVersion(1, "visible-version", "SHADOW");
        seedVersion(2, "other-version", "ACTIVE");
        seedArticle(1, "a", "2026-09-01T10:00:00Z");
        seedArticle(2, "b", "2026-09-02T10:00:00Z");
        seedArticle(3, "c", "2026-09-03T10:00:00Z");
        UUID older = seedStory(1, 1, "a", "ACTIVE", "2026-09-01T10:00:00Z");
        UUID newer = seedStory(2, 1, "b", "CLOSED", "2026-09-02T10:00:00Z");
        seedStory(3, 1, "c", "SUPERSEDED", "2026-09-03T10:00:00Z");
        seedStory(4, 2, "c", "ACTIVE", "2026-09-04T10:00:00Z");
        seedMember(2, 1, 2, 1, "NEW_COMPONENT");

        StoryPage first = service.stories(0, 1, "time", "desc");
        StoryPage second = service.stories(1, 1, "time", "desc");

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.stories()).extracting(StorySummary::id).containsExactly(newer);
        assertThat(first.stories().getFirst().memberCount()).isOne();
        assertThat(second.stories()).extracting(StorySummary::id).containsExactly(older);
    }

    @Test
    void sortsTheCompleteResultByTimeOrArticleCount() {
        seedVersion(1, "visible-version", "ACTIVE");
        seedArticle(1, "a", "2026-09-01T10:00:00Z");
        seedArticle(2, "b", "2026-09-02T10:00:00Z");
        seedArticle(3, "c", "2026-09-03T10:00:00Z");
        UUID first = seedStory(1, 1, "a", "ACTIVE", "2026-09-01T10:00:00Z");
        UUID last = seedStory(2, 1, "b", "ACTIVE", "2026-09-03T10:00:00Z");
        UUID middle = seedStory(3, 1, "c", "ACTIVE", "2026-09-02T10:00:00Z");
        seedMember(1, 1, 1, 1, "MATCHED_COMPONENT");
        seedMember(1, 1, 2, 1, "MATCHED_COMPONENT");
        seedMember(3, 1, 3, 1, "MATCHED_COMPONENT");

        assertThat(service.stories(0, 20, "time", "asc").stories())
                .extracting(StorySummary::id).containsExactly(first, middle, last);
        assertThat(service.stories(0, 20, "time", "desc").stories())
                .extracting(StorySummary::id).containsExactly(last, middle, first);
        assertThat(service.stories(0, 20, "articleCount", "asc").stories())
                .extracting(StorySummary::id).containsExactly(last, middle, first);
        assertThat(service.stories(0, 20, "articleCount", "desc").stories())
                .extracting(StorySummary::id).containsExactly(first, middle, last);
    }

    @Test
    void filtersByOverlappingTimeRangeAndCurrentMemberDomain() {
        seedVersion(1, "visible-version", "ACTIVE");
        seedArticle(1, "a", "representative.org", "2026-09-01T10:00:00Z");
        seedArticle(2, "b", "source.org", "2026-09-01T11:00:00Z");
        seedArticle(3, "c", "other.org", "2026-09-03T10:00:00Z");
        UUID matching = seedStory(1, 1, "a", "ACTIVE",
                "2026-09-01T10:00:00Z", "2026-09-02T10:00:00Z");
        seedStory(2, 1, "c", "ACTIVE",
                "2026-09-03T10:00:00Z", "2026-09-04T10:00:00Z");
        seedMember(1, 1, 1, 1, "NEW_COMPONENT");
        seedMember(1, 1, 2, 1, "MATCHED_COMPONENT");
        seedMember(2, 1, 3, 1, "NEW_COMPONENT");

        StoryPage page = service.stories(0, 1, "articleCount", "desc", new StorySearchCriteria(
                Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-03T00:00:00Z"), "source.org"));

        assertThat(page.total()).isOne();
        assertThat(page.stories()).extracting(StorySummary::id).containsExactly(matching);
    }

    @Test
    void returnsCurrentMembersAndHidesSupersededIds() {
        seedVersion(1, "visible-version", "SHADOW");
        seedArticle(1, "a", "2026-09-01T10:00:00Z");
        seedArticle(2, "b", "2026-09-01T11:00:00Z");
        UUID visible = seedStory(1, 1, "a", "ACTIVE", "2026-09-01T11:00:00Z");
        UUID replaced = seedStory(2, 1, "b", "SUPERSEDED", "2026-09-01T12:00:00Z");
        seedMember(1, 1, 1, null, "OLD");
        seedMember(1, 1, 2, 1, "MATCHED_COMPONENT");

        StoryDetail detail = service.story(visible).orElseThrow();

        assertThat(detail.members()).hasSize(1);
        assertThat(detail.members().getFirst().article().id()).isEqualTo(2);
        assertThat(detail.members().getFirst().assignmentReason()).isEqualTo("MATCHED_COMPONENT");
        assertThat(service.story(replaced)).isEmpty();
        assertThat(service.story(UUID.randomUUID())).isEmpty();
    }

    @Test
    void selectsTheSameDeterministicPrimaryLocationForListAndDetail() {
        seedVersion(1, "visible-version", "ACTIVE");
        seedArticle(1, "a", "2026-09-01T10:00:00Z");
        seedArticle(2, "b", "2026-09-01T11:00:00Z");
        seedArticle(3, "c", "2026-09-01T12:00:00Z");
        UUID located = seedStory(1, 1, "a", "ACTIVE", "2026-09-01T11:00:00Z");
        UUID unlocated = seedStory(2, 1, "c", "ACTIVE", "2026-09-01T12:00:00Z");
        seedMember(1, 1, 1, 1, "MATCHED_COMPONENT");
        seedMember(1, 1, 2, 1, "MATCHED_COMPONENT");
        seedMember(2, 1, 3, 1, "NEW_COMPONENT");
        seedGkg(1, 1, """
                [{"type":4,"name":"London","countryCode":"UK","adm1Code":"UKH9",
                  "latitude":51.5,"longitude":-0.12,"featureId":"2643743"}]
                """);
        seedGkg(2, 2, """
                [{"type":4,"name":"Invalid","countryCode":"UK","adm1Code":"UKH9",
                  "latitude":95.0,"longitude":-0.12,"featureId":"invalid"}]
                """);
        seedEvent(1, 1, 1, "Germany", "GM", null, 51.0, 10.0, null);
        seedEvent(2, 2, 4, "Berlin, Germany", "GM", "GM16", 52.52, 13.405, "-1746443");

        StoryPage page = service.stories(0, 20, "time", "desc");
        StoryLocation listLocation = page.stories().stream()
                .filter(story -> story.id().equals(located)).findFirst().orElseThrow().location();
        StoryLocation detailLocation = service.story(located).orElseThrow().location();

        assertThat(listLocation).isEqualTo(new StoryLocation(
                "Berlin, Germany", "GM", "GM16", 52.52, 13.405, "GDELT_EVENT_ACTION"));
        assertThat(detailLocation).isEqualTo(listLocation);
        assertThat(page.stories().stream()
                .filter(story -> story.id().equals(unlocated)).findFirst().orElseThrow().location()).isNull();
    }

    @Test
    void rejectsInvalidListParameters() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.stories(-1, 20, "time", "desc"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.stories(0, 0, "time", "desc"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.stories(0, 101, "time", "desc"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.stories(0, 20, "unknown", "desc"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.stories(0, 20, "time", "unknown"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.stories(0, 20, "time", "desc",
                new StorySearchCriteria(Instant.parse("2026-09-02T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z"), null)));
    }

    private void seedVersion(long id, String versionKey, String status) {
        jdbc.update("INSERT INTO story_clustering_versions VALUES (?, ?, ?)", id, versionKey, status);
    }

    private void seedArticle(long id, String ref, String seenAt) {
        seedArticle(id, ref, "example.org", seenAt);
    }

    private void seedArticle(long id, String ref, String domain, String seenAt) {
        jdbc.update("INSERT INTO articles VALUES (?, ?, ?, ?, ?)", id,
                "https://" + domain + "/" + ref, ref, domain, utc(seenAt));
    }

    private UUID seedStory(long seed, long version, String representative, String state, String effectiveTo) {
        return seedStory(seed, version, representative, state, "2026-09-01T10:00:00Z", effectiveTo);
    }

    private UUID seedStory(long seed, long version, String representative, String state,
                           String effectiveFrom, String effectiveTo) {
        UUID internalId = new UUID(0, seed);
        UUID publicId = new UUID(1, seed);
        jdbc.update("INSERT INTO stories VALUES (?, ?, ?, ?, ?, ?, ?)", internalId, publicId, version,
                representative, state, utc(effectiveFrom), utc(effectiveTo));
        return publicId;
    }

    private void seedMember(long storySeed, long version, long article, Integer current, String reason) {
        jdbc.update("INSERT INTO story_memberships VALUES (?, ?, ?, ?, ?)",
                new UUID(0, storySeed), version, article, current, reason);
    }

    private void seedEvent(long id, long articleId, int type, String name, String countryCode,
                           String regionCode, double latitude, double longitude, String featureId) {
        jdbc.update("""
                INSERT INTO gdelt_events (
                    id, article_id, action_geo_type, action_geo_name, action_geo_country_code,
                    action_geo_adm1_code, action_geo_latitude, action_geo_longitude, action_geo_feature_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, articleId, type, name, countryCode, regionCode, latitude, longitude, featureId);
    }

    private void seedGkg(long id, long articleId, String locations) {
        jdbc.update("""
                INSERT INTO gdelt_gkg (id, article_id, source_timestamp, locations)
                VALUES (?, ?, ?, CAST(? AS JSON))
                """, id, articleId, utc("2026-09-01T10:00:00Z"), locations);
    }

    private OffsetDateTime utc(String instant) {
        return Instant.parse(instant).atOffset(ZoneOffset.UTC);
    }
}
