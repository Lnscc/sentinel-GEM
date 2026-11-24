package com.example.globalnewsenginev1.stories.snapshot;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StoryLocationMaterializerTests {
    private JdbcTemplate jdbc;
    private StoryLocationMaterializer materializer;
    private final UUID storyId = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:story-locations-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE stories (id UUID PRIMARY KEY, clustering_version_id BIGINT, state VARCHAR(16))");
        jdbc.execute("CREATE TABLE story_memberships (story_id UUID, clustering_version_id BIGINT, article_id BIGINT, current_marker SMALLINT)");
        jdbc.execute("CREATE TABLE articles (id BIGINT PRIMARY KEY, domain TEXT)");
        jdbc.execute("CREATE TABLE gdelt_events (article_id BIGINT, global_event_id BIGINT, source_url TEXT, action_geo_type INTEGER, action_geo_name TEXT, action_geo_country_code VARCHAR(16), action_geo_adm1_code VARCHAR(32), action_geo_latitude DOUBLE PRECISION, action_geo_longitude DOUBLE PRECISION, action_geo_feature_id TEXT)");
        jdbc.execute("CREATE TABLE gdelt_mentions (global_event_id BIGINT, mention_identifier TEXT, confidence INTEGER)");
        jdbc.execute("CREATE TABLE story_locations (story_id UUID, rank INTEGER, name TEXT, country_code VARCHAR(16), region_code VARCHAR(32), latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, source VARCHAR(64), confidence INTEGER, supporting_articles INTEGER, supporting_domains INTEGER, PRIMARY KEY (story_id, rank))");
        materializer = new StoryLocationMaterializer(jdbc);
    }

    @Test
    void replacesRankedLocationsAndRemovesStaleRows() {
        jdbc.update("INSERT INTO stories VALUES (?, 1, 'ACTIVE')", storyId);
        jdbc.update("INSERT INTO articles VALUES (1, 'one.example'), (2, 'two.example')");
        jdbc.update("INSERT INTO story_memberships VALUES (?, 1, 1, 1), (?, 1, 2, 1)", storyId, storyId);
        seedEvent(1, 1, "Berlin", "GM", "GM16", 52.52, 13.405, 90);
        seedEvent(2, 2, "Berlin", "GM", "GM16", 52.52, 13.405, 100);
        seedEvent(3, 2, "London", "UK", "UKI", 51.5, -0.12, 80);

        materializer.refresh(1);
        materializer.refresh(1);

        assertThat(jdbc.queryForList("SELECT name, rank, confidence, supporting_articles, supporting_domains FROM story_locations ORDER BY rank"))
                .containsExactly(
                        java.util.Map.of("NAME", "Berlin", "RANK", 1, "CONFIDENCE", 100,
                                "SUPPORTING_ARTICLES", 2, "SUPPORTING_DOMAINS", 2),
                        java.util.Map.of("NAME", "London", "RANK", 2, "CONFIDENCE", 80,
                                "SUPPORTING_ARTICLES", 1, "SUPPORTING_DOMAINS", 1));

        jdbc.update("UPDATE stories SET state = 'SUPERSEDED' WHERE id = ?", storyId);
        materializer.refresh(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_locations", Integer.class)).isZero();
    }

    @Test
    void scopedRefreshPreservesOtherStoriesAndClearsSupersededLocations() {
        UUID otherStory = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID otherVersionStory = UUID.fromString("10000000-0000-0000-0000-000000000003");
        jdbc.update("INSERT INTO stories VALUES (?, 1, 'ACTIVE'), (?, 1, 'ACTIVE'), (?, 2, 'ACTIVE')",
                storyId, otherStory, otherVersionStory);
        jdbc.update("INSERT INTO articles VALUES (1, 'one.example'), (2, 'two.example'), (3, 'three.example')");
        jdbc.update("INSERT INTO story_memberships VALUES (?, 1, 1, 1), (?, 1, 2, 1), (?, 2, 3, 1)",
                storyId, otherStory, otherVersionStory);
        seedEvent(1, 1, "Berlin", "GM", "GM16", 52.52, 13.405, 90);
        seedEvent(2, 2, "London", "UK", "UKI", 51.5, -0.12, 80);
        seedEvent(3, 3, "Paris", "FR", "FR11", 48.86, 2.35, 70);
        materializer.refresh(1);
        materializer.refresh(2);
        jdbc.update("UPDATE gdelt_mentions SET confidence = 100 WHERE global_event_id = 1");

        materializer.refresh(1, Set.of(storyId, otherVersionStory));

        assertThat(jdbc.queryForObject("SELECT confidence FROM story_locations WHERE story_id = ?",
                Integer.class, storyId)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_locations", Integer.class)).isEqualTo(3);
        jdbc.update("UPDATE stories SET state = 'SUPERSEDED' WHERE id = ?", storyId);
        materializer.refresh(1, Set.of(storyId));
        assertThat(jdbc.queryForList("SELECT name FROM story_locations ORDER BY name", String.class))
                .containsExactly("London", "Paris");

        jdbc.update("DELETE FROM gdelt_events");
        materializer.refresh(1, Set.of());
        assertThat(jdbc.queryForList("SELECT name FROM story_locations ORDER BY name", String.class))
                .containsExactly("London", "Paris");
    }

    private void seedEvent(long eventId, long articleId, String name, String country, String region,
                           double latitude, double longitude, int confidence) {
        String source = "https://source/" + eventId;
        jdbc.update("INSERT INTO gdelt_events VALUES (?, ?, ?, 4, ?, ?, ?, ?, ?, ?)",
                articleId, eventId, source, name, country, region, latitude, longitude, Long.toString(eventId));
        jdbc.update("INSERT INTO gdelt_mentions VALUES (?, ?, ?)", eventId, source, confidence);
    }
}
