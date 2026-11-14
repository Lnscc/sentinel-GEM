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
                    global_event_id BIGINT, source_url TEXT,
                    action_geo_type INTEGER, action_geo_name TEXT, action_geo_country_code VARCHAR(16),
                    action_geo_adm1_code VARCHAR(32), action_geo_latitude DOUBLE PRECISION,
                    action_geo_longitude DOUBLE PRECISION, action_geo_feature_id VARCHAR(255))
                """);
        jdbc.execute("""
                CREATE TABLE gdelt_mentions (id BIGINT PRIMARY KEY, global_event_id BIGINT,
                    mention_identifier TEXT, confidence INTEGER)
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
    void readsRepresentativeMetadataAndCountFromThePublishedProjection() {
        seedVersion(1, "visible-version", "ACTIVE");
        seedArticle(1, "a", "publisher.example", "2026-09-01T10:00:00Z");
        seedArticle(2, "b", "member.example", "2026-09-01T11:00:00Z");
        UUID storyId = seedStory(1, 1, "a", "ACTIVE", "2026-09-01T10:00:00Z");
        seedMember(1, 1, 1, 1, "NEW_COMPONENT");
        jdbc.update("""
                UPDATE stories
                SET representative_title = 'Published title',
                    representative_published_at = ?,
                    representative_main_image_url = 'https://publisher.example/image.jpg',
                    source_count = 1
                WHERE public_id = ?
                """, utc("2026-09-01T09:00:00Z"), storyId);
        jdbc.update("INSERT INTO story_memberships VALUES (?, 1, 2, 1, 'MATCHED_COMPONENT')",
                new UUID(0, 1));

        StorySummary story = service.stories(0, 20, "articleCount", "desc").stories().getFirst();

        assertThat(story.memberCount()).isOne();
        assertThat(story.sourceCount()).isOne();
        assertThat(story.representativeArticle().title()).isEqualTo("Published title");
        assertThat(story.representativeArticle().publishedAt())
                .isEqualTo(Instant.parse("2026-09-01T09:00:00Z"));
        assertThat(story.representativeArticle().mainImageUrl())
                .isEqualTo("https://publisher.example/image.jpg");
        assertThat(story.representativeArticle().domain()).isEqualTo("publisher.example");
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
    void prioritizesIndependentUkraineCoverageOverSyndicatedOutlookCopiesDeterministically() {
        seedVersion(1, "visible-version", "ACTIVE");
        seedArticle(1, "outlook", "syndication.example", "2026-09-01T12:00:00Z");
        seedArticle(2, "ukraine-a", "independent-a.example", "2026-09-01T11:00:00Z");
        seedArticle(3, "ukraine-b", "independent-b.example", "2026-09-01T11:00:00Z");
        UUID outlook = seedStory(1, 1, "outlook", "ACTIVE", "2026-09-01T12:00:00Z");
        UUID firstUkraine = seedStory(2, 1, "ukraine-a", "ACTIVE", "2026-09-01T11:00:00Z");
        UUID secondUkraine = seedStory(3, 1, "ukraine-b", "ACTIVE", "2026-09-01T11:00:00Z");
        seedProjectionCounts(1, 12, 12, 1);
        seedProjectionCounts(2, 3, 3, 3);
        seedProjectionCounts(3, 3, 3, 3);

        assertThat(service.stories(0, 20, "priority", "desc").stories())
                .extracting(StorySummary::id)
                .containsExactly(firstUkraine, secondUkraine, outlook);
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
                Instant.parse("2026-09-03T00:00:00Z"), "source.org", null, null));

        assertThat(page.total()).isOne();
        assertThat(page.stories()).extracting(StorySummary::id).containsExactly(matching);
    }

    @Test
    void filtersByPrimaryLocationBeforePagination() {
        seedVersion(1, "visible-version", "ACTIVE");
        seedArticle(1, "a", "berlin-one.org", "2026-09-01T10:00:00Z");
        seedArticle(2, "b", "potsdam-one.org", "2026-09-02T10:00:00Z");
        seedArticle(3, "c", "london-one.org", "2026-09-03T10:00:00Z");
        seedArticle(4, "d", "berlin-two.org", "2026-09-01T11:00:00Z");
        seedArticle(5, "e", "potsdam-two.org", "2026-09-02T11:00:00Z");
        seedArticle(6, "f", "london-two.org", "2026-09-03T11:00:00Z");
        seedStory(1, 1, "a", "ACTIVE", "2026-09-01T10:00:00Z");
        UUID secondGermanStory = seedStory(2, 1, "b", "ACTIVE", "2026-09-02T10:00:00Z");
        seedStory(3, 1, "c", "ACTIVE", "2026-09-03T10:00:00Z");
        seedMember(1, 1, 1, 1, "NEW_COMPONENT");
        seedMember(1, 1, 4, 1, "MATCHED_COMPONENT");
        seedMember(2, 1, 2, 1, "NEW_COMPONENT");
        seedMember(2, 1, 5, 1, "MATCHED_COMPONENT");
        seedMember(3, 1, 3, 1, "NEW_COMPONENT");
        seedMember(3, 1, 6, 1, "MATCHED_COMPONENT");
        seedEvent(1, 1, 4, "Berlin, Germany", "GM", "GM16", 52.52, 13.405, "-1746443");
        seedEvent(2, 4, 4, "Berlin, Germany", "GM", "GM16", 52.52, 13.405, "-1746443");
        seedEvent(3, 2, 4, "Potsdam, Germany", "GM", "GM16", 52.39, 13.06, "-1743137");
        seedEvent(4, 5, 4, "Potsdam, Germany", "GM", "GM16", 52.39, 13.06, "-1743137");
        seedEvent(5, 3, 4, "London, United Kingdom", "UK", "UKI", 51.5, -0.12, "2643743");
        seedEvent(6, 6, 4, "London, United Kingdom", "UK", "UKI", 51.5, -0.12, "2643743");
        seedEvidence(1, 1, "Berlin incident");
        seedEvidence(2, 4, "Incident confirmed in Berlin");
        seedEvidence(3, 2, "Potsdam incident");
        seedEvidence(4, 5, "Incident confirmed in Potsdam");
        seedEvidence(5, 3, "London incident");
        seedEvidence(6, 6, "Incident confirmed in London");
        seedLocation(1, "Berlin, Germany", "GM", "GM16", 52.52, 13.405, 100, 2, 2);
        seedLocation(2, "Potsdam, Germany", "GM", "GM16", 52.39, 13.06, 100, 2, 2);
        seedLocation(3, "London, United Kingdom", "UK", "UKI", 51.5, -0.12, 100, 2, 2);

        StoryPage page = service.stories(1, 1, "time", "asc",
                new StorySearchCriteria(null, null, null, "GM", "GM16"));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.stories()).extracting(StorySummary::id).containsExactly(secondGermanStory);
        assertThat(page.stories().getFirst().location().regionCode()).isEqualTo("GM16");
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
        seedArticle(1, "a", "first.org", "2026-09-01T10:00:00Z");
        seedArticle(2, "b", "second.org", "2026-09-01T11:00:00Z");
        seedArticle(3, "c", "2026-09-01T12:00:00Z");
        UUID located = seedStory(1, 1, "a", "ACTIVE", "2026-09-01T11:00:00Z");
        UUID unlocated = seedStory(2, 1, "c", "ACTIVE", "2026-09-01T12:00:00Z");
        seedMember(1, 1, 1, 1, "MATCHED_COMPONENT");
        seedMember(1, 1, 2, 1, "MATCHED_COMPONENT");
        seedMember(2, 1, 3, 1, "NEW_COMPONENT");
        seedEvidence(1, 1, "Fire in Berlin");
        seedEvidence(2, 2, "Berlin fire confirmed");
        seedGkg(3, 3, "Outlook outage", """
                [{"type":1,"name":"Canada","countryCode":"CA","adm1Code":"CA",
                  "latitude":60.0,"longitude":-96.0,"featureId":"CA"}]
                """);
        seedEvent(1, 1, 4, "Berlin, Germany", "GM", "GM16", 52.52, 13.405, "-1746443");
        seedEvent(2, 2, 4, "Berlin, Germany", "GM", "GM16", 52.52, 13.405, "-1746443");
        seedLocation(1, "Berlin, Germany", "GM", "GM16", 52.52, 13.405, 100, 2, 2);

        StoryPage page = service.stories(0, 20, "time", "desc");
        StoryLocation listLocation = page.stories().stream()
                .filter(story -> story.id().equals(located)).findFirst().orElseThrow().location();
        StoryLocation detailLocation = service.story(located).orElseThrow().location();

        assertThat(listLocation).isEqualTo(new StoryLocation(
                "Berlin, Germany", "GM", "GM16", 52.52, 13.405, "GDELT_EVENT_ACTION",
                100, 2, 2));
        assertThat(detailLocation).isEqualTo(listLocation);
        assertThat(service.story(located).orElseThrow().locations()).containsExactly(listLocation);
        assertThat(page.stories().stream()
                .filter(story -> story.id().equals(unlocated)).findFirst().orElseThrow().location()).isNull();
    }

    @Test
    void keepsAllEventLocationsAndUsesTheHighestConfidenceAsPrimary() {
        seedVersion(1, "visible-version", "ACTIVE");
        seedArticle(1, "outlook", "bbc.co.uk", "2026-09-01T10:00:00Z");
        seedArticle(2, "ukraine", "adn.com", "2026-09-01T11:00:00Z");
        UUID outlook = seedStory(1, 1, "outlook", "ACTIVE", "2026-09-01T10:00:00Z");
        UUID ukraine = seedStory(2, 1, "ukraine", "ACTIVE", "2026-09-01T11:00:00Z");
        seedMember(1, 1, 1, 1, "NEW_COMPONENT");
        seedMember(2, 1, 2, 1, "NEW_COMPONENT");
        seedGkg(1, 1, "Microsoft Outlook outage", "[]");
        seedGkg(2, 2, "Ukraine launches drones at Russia", "[]");
        seedEvent(1, 1, 1, "United Kingdom", "UK", null, 54, -4, "UK");
        seedEvent(2, 2, 4, "Kharkiv, Ukraine", "UP", "UP07", 49.9808, 36.2527, "-1041944");
        seedEvent(3, 2, 4, "Moscow, Russia", "RS", "RS48", 55.7522, 37.6156, "-2960561");
        seedMention(1, 50);
        seedMention(2, 100);
        seedMention(3, 90);
        seedLocation(1, "United Kingdom", "UK", null, 54, -4, 50, 1, 1);
        seedLocation(2, "Kharkiv, Ukraine", "UP", "UP07", 49.9808, 36.2527, 100, 1, 1);
        seedLocation(2, 2, "Moscow, Russia", "RS", "RS48", 55.7522, 37.6156, 90, 1, 1);

        StoryDetail outlookDetail = service.story(outlook).orElseThrow();
        assertThat(outlookDetail.location().name()).isEqualTo("United Kingdom");
        assertThat(outlookDetail.locations()).singleElement().satisfies(location -> {
            assertThat(location.name()).isEqualTo("United Kingdom");
            assertThat(location.confidence()).isEqualTo(50);
        });

        StoryDetail ukraineDetail = service.story(ukraine).orElseThrow();
        assertThat(ukraineDetail.location().name()).isEqualTo("Kharkiv, Ukraine");
        assertThat(ukraineDetail.locations()).extracting(StoryLocation::name)
                .containsExactly("Kharkiv, Ukraine", "Moscow, Russia");
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
                        Instant.parse("2026-09-01T00:00:00Z"), null, null, null)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.stories(0, 20, "time", "desc",
                new StorySearchCriteria(null, null, null, "GM!", null)));
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
        jdbc.update("""
                INSERT INTO stories (
                    id, public_id, clustering_version_id, representative_article_ref, state,
                    effective_from, effective_to, representative_article_id,
                    representative_canonical_url, representative_domain,
                    representative_first_seen_at, member_count)
                SELECT ?, ?, ?, ?, ?, ?, ?, article.id, article.canonical_url, article.domain,
                       article.first_seen_at, 0
                FROM articles article WHERE article.url_hash = ?
                """, internalId, publicId, version, representative, state,
                utc(effectiveFrom), utc(effectiveTo), representative);
        return publicId;
    }

    private void seedMember(long storySeed, long version, long article, Integer current, String reason) {
        jdbc.update("INSERT INTO story_memberships VALUES (?, ?, ?, ?, ?)",
                new UUID(0, storySeed), version, article, current, reason);
        if (Integer.valueOf(1).equals(current)) {
            jdbc.update("UPDATE stories SET member_count = member_count + 1 WHERE id = ?",
                    new UUID(0, storySeed));
        }
    }

    private void seedProjectionCounts(long storySeed, int members, int sources, int independentReports) {
        jdbc.update("""
                UPDATE stories
                SET member_count = ?, source_count = ?, independent_report_count = ?
                WHERE id = ?
                """, members, sources, independentReports, new UUID(0, storySeed));
    }

    private void seedEvent(long id, long articleId, int type, String name, String countryCode,
                           String regionCode, double latitude, double longitude, String featureId) {
        jdbc.update("""
                INSERT INTO gdelt_events (
                    id, article_id, global_event_id, source_url,
                    action_geo_type, action_geo_name, action_geo_country_code,
                    action_geo_adm1_code, action_geo_latitude, action_geo_longitude, action_geo_feature_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, articleId, id, "https://source/" + id, type, name, countryCode,
                regionCode, latitude, longitude, featureId);
    }

    private void seedEvidence(long eventId, long articleId, String title) {
        seedGkg(eventId, articleId, title, "[]");
        seedMention(eventId, 100);
    }

    private void seedLocation(long storySeed, String name, String country, String region,
                              double latitude, double longitude, int confidence, int articles, int domains) {
        seedLocation(storySeed, 1, name, country, region, latitude, longitude, confidence, articles, domains);
    }

    private void seedLocation(long storySeed, int rank, String name, String country, String region,
                              double latitude, double longitude, int confidence, int articles, int domains) {
        jdbc.update("INSERT INTO story_locations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new UUID(0, storySeed), rank, name, country, region, latitude, longitude,
                "GDELT_EVENT_ACTION", confidence, articles, domains);
    }

    private void seedMention(long eventId, int confidence) {
        jdbc.update("INSERT INTO gdelt_mentions VALUES (?, ?, ?, ?)", eventId, eventId,
                "https://source/" + eventId, confidence);
    }

    private void seedGkg(long id, long articleId, String title, String locations) {
        jdbc.update("""
                INSERT INTO gdelt_gkg (id, article_id, source_timestamp, page_title, locations)
                VALUES (?, ?, ?, ?, CAST(? AS JSON))
                """, id, articleId, utc("2026-09-01T10:00:00Z"), title, locations);
    }

    private OffsetDateTime utc(String instant) {
        return Instant.parse(instant).atOffset(ZoneOffset.UTC);
    }
}
