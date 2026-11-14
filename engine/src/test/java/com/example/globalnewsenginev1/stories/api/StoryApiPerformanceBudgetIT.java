package com.example.globalnewsenginev1.stories.api;

import com.example.globalnewsenginev1.stories.query.StoryQueryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class StoryApiPerformanceBudgetIT {

    private static final int STORY_COUNT = 2_000;
    private static final int WARM_RUNS = 20;
    private static final UUID DETAIL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final AtomicLong DB_NANOS = new AtomicLong();

    private static DataSource adminDataSource;
    private static MockMvc api;
    private static String schema;

    @BeforeAll
    static void setUp() {
        adminDataSource = postgres(null);
        Assumptions.assumeTrue(canConnect(adminDataSource),
                "PostgreSQL is not available for story API performance budgets");
        schema = "story_api_budget_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA " + schema);
        JdbcTemplate seed = new JdbcTemplate(postgres(schema));
        createSchema(seed);
        seedData(seed);
        StoryQueryService service = new StoryQueryService(new JdbcTemplate(timed(postgres(schema))), "budget-v1");
        api = standaloneSetup(new StoryController(service)).build();
    }

    @AfterAll
    static void tearDown() {
        if (schema != null) {
            new JdbcTemplate(adminDataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void storyReadsStayWithinBudgets() throws Exception {
        measure("list", "/stories?offset=0&limit=20&sort=time&direction=desc", 250, 220, 35_000);
        measure("region", "/stories?offset=0&limit=100&sort=priority&direction=desc&region=R1",
                1_200, 1_100, 170_000);
        measure("detail", "/stories/" + DETAIL_ID, 200, 180, 12_000);
    }

    @Test
    void regionQueryUsesTheMaterializedLocationIndex() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres(schema));
        String plan = jdbc.queryForObject("""
                EXPLAIN (ANALYZE, FORMAT JSON)
                SELECT story.id
                FROM story_locations location
                JOIN stories story ON story.id = location.story_id
                WHERE location.country_code = 'C1'
                  AND location.region_code = 'R1'
                  AND location.rank = 1
                ORDER BY story.effective_to DESC
                LIMIT 100
                """, (resultSet, rowNum) -> resultSet.getString(1));

        assertThat(plan).contains("idx_story_locations_primary_country_region");

        String regionOnlyPlan = jdbc.queryForObject("""
                EXPLAIN (ANALYZE, FORMAT JSON)
                SELECT story.id
                FROM story_locations location
                JOIN stories story ON story.id = location.story_id
                WHERE location.region_code = 'R1'
                  AND location.rank = 1
                ORDER BY story.effective_to DESC
                LIMIT 100
                """, (resultSet, rowNum) -> resultSet.getString(1));

        assertThat(regionOnlyPlan).contains("idx_story_locations_primary_region");
    }

    @Test
    void storyListUsesMaterializedValues() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres(schema));
        String plan = jdbc.queryForObject("""
                EXPLAIN (ANALYZE, FORMAT JSON)
                SELECT story.public_id, story.representative_title, story.member_count, story.source_count
                FROM stories story
                JOIN story_clustering_versions version
                  ON version.id = story.clustering_version_id AND version.version_key = 'budget-v1'
                WHERE story.state <> 'SUPERSEDED'
                ORDER BY story.independent_report_count DESC, story.effective_to DESC, story.public_id
                LIMIT 20
                """, (resultSet, rowNum) -> resultSet.getString(1));

        assertThat(plan).contains("\"Relation Name\": \"stories\"")
                .doesNotContain("story_memberships", "gdelt_gkg", "SubPlan");
    }

    private void measure(String name, String path, long responseP95BudgetMs, long databaseP95BudgetMs,
                         int responseBytesBudget) throws Exception {
        Sample cold = request(path);
        List<Sample> warm = new ArrayList<>();
        for (int run = 0; run < WARM_RUNS; run++) warm.add(request(path));
        long responseP50 = percentile(warm, Sample::responseNanos, 0.50);
        long responseP95 = percentile(warm, Sample::responseNanos, 0.95);
        long databaseP50 = percentile(warm, Sample::databaseNanos, 0.50);
        long databaseP95 = percentile(warm, Sample::databaseNanos, 0.95);
        int bytes = warm.getFirst().bytes();
        System.out.printf("PERF %-6s stories=%d cold=%dms warm-response-p50/p95=%d/%dms "
                        + "warm-db-p50/p95=%d/%dms bytes=%d%n",
                name, STORY_COUNT, millis(cold.responseNanos()), millis(responseP50), millis(responseP95),
                millis(databaseP50), millis(databaseP95), bytes);
        assertThat(millis(responseP95)).as(name + " response p95").isLessThanOrEqualTo(responseP95BudgetMs);
        assertThat(millis(databaseP95)).as(name + " database p95").isLessThanOrEqualTo(databaseP95BudgetMs);
        assertThat(bytes).as(name + " response bytes").isLessThanOrEqualTo(responseBytesBudget);
    }

    private static Sample request(String path) throws Exception {
        DB_NANOS.set(0);
        long started = System.nanoTime();
        byte[] content = api.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new Sample(System.nanoTime() - started, DB_NANOS.get(), content.length);
    }

    private static long percentile(List<Sample> samples, java.util.function.ToLongFunction<Sample> value,
                                   double percentile) {
        List<Long> sorted = samples.stream().map(value::applyAsLong).sorted(Comparator.naturalOrder()).toList();
        return sorted.get((int) Math.ceil(percentile * sorted.size()) - 1);
    }

    private static long millis(long nanos) {
        return Math.max(1, Math.round(nanos / 1_000_000d));
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE story_clustering_versions (id BIGINT PRIMARY KEY, version_key VARCHAR(128), status VARCHAR(16))");
        jdbc.execute("CREATE TABLE articles (id BIGINT PRIMARY KEY, canonical_url TEXT, url_hash VARCHAR(64), domain TEXT, first_seen_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE gdelt_gkg (id BIGINT PRIMARY KEY, article_id BIGINT, source_timestamp TIMESTAMPTZ, page_title TEXT, page_precise_pub_timestamp TIMESTAMPTZ, main_image_url TEXT)");
        jdbc.execute("CREATE TABLE gdelt_events (id BIGINT PRIMARY KEY, article_id BIGINT, global_event_id BIGINT, source_url TEXT, action_geo_type INTEGER, action_geo_name TEXT, action_geo_country_code VARCHAR(16), action_geo_adm1_code VARCHAR(32), action_geo_latitude DOUBLE PRECISION, action_geo_longitude DOUBLE PRECISION, action_geo_feature_id VARCHAR(255))");
        jdbc.execute("CREATE TABLE gdelt_mentions (id BIGINT PRIMARY KEY, global_event_id BIGINT, mention_identifier TEXT, confidence INTEGER)");
        jdbc.execute("CREATE TABLE stories (id UUID PRIMARY KEY, public_id UUID, clustering_version_id BIGINT, representative_article_ref VARCHAR(64), state VARCHAR(16), effective_from TIMESTAMPTZ, effective_to TIMESTAMPTZ, representative_article_id BIGINT, representative_canonical_url TEXT, representative_domain TEXT, representative_first_seen_at TIMESTAMPTZ, representative_title TEXT, representative_published_at TIMESTAMPTZ, representative_main_image_url TEXT, member_count INTEGER, source_count INTEGER, independent_report_count INTEGER)");
        jdbc.execute("CREATE TABLE story_memberships (story_id UUID, clustering_version_id BIGINT, article_id BIGINT, current_marker SMALLINT, assignment_reason TEXT)");
        jdbc.execute("CREATE TABLE story_locations (story_id UUID, rank INTEGER, name TEXT, country_code VARCHAR(16), region_code VARCHAR(32), latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, source VARCHAR(64), confidence INTEGER, supporting_articles INTEGER, supporting_domains INTEGER)");
        jdbc.execute("CREATE INDEX idx_story_locations_primary_country_region ON story_locations (country_code, region_code, rank, story_id)");
        jdbc.execute("CREATE INDEX idx_story_locations_primary_region ON story_locations (region_code, rank, story_id)");
        jdbc.execute("CREATE INDEX idx_stories_list_time ON stories (clustering_version_id, effective_to DESC, public_id)");
        jdbc.execute("CREATE INDEX idx_stories_list_member_count ON stories (clustering_version_id, member_count DESC, public_id)");
        jdbc.execute("CREATE INDEX idx_stories_list_priority ON stories (clustering_version_id, independent_report_count DESC, effective_to DESC, public_id)");
        jdbc.execute("CREATE INDEX ON story_memberships (story_id, clustering_version_id, current_marker)");
        jdbc.execute("CREATE INDEX ON gdelt_events (article_id)");
        jdbc.execute("CREATE INDEX ON gdelt_mentions (global_event_id, mention_identifier)");
    }

    private static void seedData(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO story_clustering_versions VALUES (1, 'budget-v1', 'ACTIVE')");
        jdbc.update("""
                INSERT INTO articles
                SELECT i, 'https://news.example/story-' || i, md5(i::text),
                       'source-' || (i % 40) || '.example', now() - (i || ' minutes')::interval
                FROM generate_series(1, ?) i
                """, STORY_COUNT * 3);
        jdbc.update("""
                INSERT INTO gdelt_gkg
                SELECT i, i, now() - (i || ' minutes')::interval, 'Representative story title ' || i,
                       now() - (i || ' minutes')::interval, 'https://images.example/' || i || '.jpg'
                FROM generate_series(1, ?) i
                """, STORY_COUNT * 3);
        jdbc.update("""
                INSERT INTO stories
                SELECT ('10000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
                       ('00000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
                       1, md5(i::text), 'ACTIVE', now() - (i || ' minutes')::interval,
                       now() - (i || ' minutes')::interval,
                       i, 'https://news.example/story-' || i,
                       'source-' || (i % 40) || '.example', now() - (i || ' minutes')::interval,
                       'Representative story title ' || i, now() - (i || ' minutes')::interval,
                       'https://images.example/' || i || '.jpg', 3, 3, (i % 3) + 1
                FROM generate_series(1, ?) i
                """, STORY_COUNT);
        jdbc.update("""
                INSERT INTO story_memberships
                SELECT ('10000000-0000-0000-0000-' || lpad(story::text, 12, '0'))::uuid,
                       1, story + member * ?, 1, 'MATCHED_COMPONENT'
                FROM generate_series(1, ?) story CROSS JOIN generate_series(0, 2) member
                """, STORY_COUNT, STORY_COUNT);
        jdbc.update("""
                INSERT INTO gdelt_events
                SELECT i, i, i, 'https://news.example/story-' || i, 3, 'Location ' || (i % 120),
                       'C' || (i % 12), 'R' || (i % 4), (i % 120) - 60, (i % 300) - 150, i::text
                FROM generate_series(1, ?) i
                """, STORY_COUNT * 3);
        jdbc.update("""
                INSERT INTO gdelt_mentions
                SELECT i, i, 'https://news.example/story-' || i, 80
                FROM generate_series(1, ?) i
                """, STORY_COUNT * 3);
        jdbc.update("""
                INSERT INTO story_locations
                SELECT story.id, 1, 'Location ' || (i % 120), 'C' || (i % 12), 'R' || (i % 200),
                       (i % 120) - 60, (i % 300) - 150, 'GDELT_EVENT_ACTION', 80, 3, 3
                FROM stories story
                JOIN generate_series(1, ?) i ON story.public_id =
                    ('00000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid
                """, STORY_COUNT);
        jdbc.execute("ANALYZE");
    }

    private static DataSource timed(DataSource delegate) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        return result instanceof Connection connection ? timedConnection(connection) : result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static Connection timedConnection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        return result instanceof Statement statement ? timedStatement(statement) : result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static Statement timedStatement(Statement delegate) {
        Class<?> type = delegate instanceof java.sql.PreparedStatement
                ? java.sql.PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
            long started = method.getName().startsWith("execute") ? System.nanoTime() : 0;
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            } finally {
                if (started != 0) DB_NANOS.addAndGet(System.nanoTime() - started);
            }
        });
    }

    private static PGSimpleDataSource postgres(String currentSchema) {
        PGSimpleDataSource postgres = new PGSimpleDataSource();
        String url = System.getProperty("it.postgres.jdbc-url", "jdbc:postgresql://localhost:5432/gne");
        if (currentSchema != null) url += (url.contains("?") ? "&" : "?") + "currentSchema=" + currentSchema;
        postgres.setUrl(url);
        postgres.setUser(System.getProperty("it.postgres.username", "gne"));
        postgres.setPassword(System.getProperty("it.postgres.password", "gne"));
        return postgres;
    }

    private static boolean canConnect(DataSource candidate) {
        try (var ignored = candidate.getConnection()) {
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private record Sample(long responseNanos, long databaseNanos, int bytes) {}
}
