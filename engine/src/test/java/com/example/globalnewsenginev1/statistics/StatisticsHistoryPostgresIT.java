package com.example.globalnewsenginev1.statistics;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import com.example.globalnewsenginev1.stories.EmbeddingUsageMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Opt-in isolated history benchmark; never defaults to a production or compose database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatisticsHistoryPostgresIT {
    private JdbcTemplate jdbc;
    private JdbcTemplate admin;
    private TransactionTemplate transaction;
    private DataSourceTransactionManager manager;
    private String schema;

    @BeforeAll
    void fixture() throws Exception {
        String url = System.getenv("GEM128_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "Requires explicit isolated GEM128_JDBC_URL");
        PGSimpleDataSource source = source(url);
        admin = new JdbcTemplate(source);
        schema = "gem128_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        source.setCurrentSchema(schema);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        Path script = Path.of("scripts/gem128-statistics/fixture.sql");
        if (!Files.exists(script)) script = Path.of("../scripts/gem128-statistics/fixture.sql");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
        }
    }

    @AfterAll
    void cleanup() {
        if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void countsProductionScaleHistoryWithinRequestBudget() {
        long started = System.nanoTime();
        double count = optimized("2026-11-27T12:00:00Z", "2026-11-28T12:00:00Z", 1);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(count).isEqualTo(194002);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(45));
        System.out.printf("GEM-128 counter: 194002 logical articles, 267 archives, %d ms%n", elapsed.toMillis());
    }

    @Test
    void normal24HourHttpReportReturnsExactStatisticsBeforeGatewayDeadline() throws Exception {
        StatisticsService target = new StatisticsService(jdbc,
                new EmbeddingUsageMetrics(new SimpleMeterRegistry()), "visible",
                Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), ZoneOffset.UTC));
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        StatisticsService service = (StatisticsService) factory.getProxy();
        var mvc = standaloneSetup(new StatisticsController(service))
                .setControllerAdvice(new StatisticsExceptionHandler()).build();
        long started = System.nanoTime();
        var pending = mvc.perform(get("/statistics")).andReturn();
        pending.getAsyncResult(60_000);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics[?(@.id == 'articles')].value").value(org.hamcrest.Matchers.contains(194002.0)))
                .andExpect(jsonPath("$.metrics[?(@.id == 'processedArticles')].value").value(org.hamcrest.Matchers.contains(194002.0)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(45));
        System.out.printf("GEM-128 HTTP 200 full report: %d ms%n", elapsed.toMillis());
    }

    @Test
    void equalsExactReferenceForFullArchivePartialCompactionCurrentAndEmptyPeriods() {
        for (String[] period : new String[][] {
                {"2026-11-28T09:00:00Z", "2026-11-28T09:00:01Z"},
                {"2026-11-28T11:00:00Z", "2026-11-28T12:00:00Z"},
                {"2026-11-28T11:01:00Z", "2026-11-28T12:00:00Z"},
                {"2026-11-28T12:00:00Z", "2026-11-28T13:00:00Z"}
        }) {
            assertThat(optimized(period[0], period[1], 1)).isEqualTo(reference(period[0], period[1], 1));
        }
        assertThat(optimized("2026-11-28T10:00:00Z", "2026-11-28T12:00:00Z", 2)).isEqualTo(1);
    }

    private double optimized(String from, String to, long version) {
        return transaction.execute(status -> {
            try (var budget = new StatisticsQueryBudget()) {
                budget.bind();
                return ProcessedArticleCounter.count(jdbc, version, timestamp(from), timestamp(to));
            }
        });
    }

    @Test
    void cancelsBlockedArchiveReadAfterSnapshotSelection() {
        new TransactionTemplate(manager).execute(status -> {
            jdbc.execute("SET LOCAL search_path = pg_temp, " + schema);
            jdbc.execute("CREATE TEMP TABLE story_snapshot_members AS SELECT * FROM "
                    + schema + ".story_snapshot_members WHERE FALSE");
            jdbc.execute("""
                    CREATE FUNCTION pg_temp.slow_archive(ids BIGINT[]) RETURNS BIGINT[] LANGUAGE plpgsql STABLE AS $$
                    BEGIN PERFORM pg_sleep(10); RETURN ids; END $$
                    """);
            jdbc.execute("""
                    CREATE TEMP VIEW story_snapshot_retention AS
                    SELECT snapshot_id, pg_temp.slow_archive(article_id_deltas) AS article_id_deltas
                    FROM %s.story_snapshot_retention
                    """.formatted(schema));
            long started = System.nanoTime();
            var scoped = spy(new StatisticsJdbcTemplate(jdbc));
            try (var budget = new StatisticsQueryBudget(Duration.ofSeconds(2))) {
                budget.bind();
                assertThatThrownBy(() -> ProcessedArticleCounter.count(scoped, 1,
                        timestamp("2026-11-28T09:00:00Z"), timestamp("2026-11-28T09:00:01Z")))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
            verify(scoped).query(eq("SELECT article_id_deltas FROM story_snapshot_retention WHERE snapshot_id = ?"),
                    any(org.springframework.jdbc.core.RowCallbackHandler.class), anyLong());
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            status.setRollbackOnly();
            return null;
        });
    }

    private double reference(String from, String to, long version) {
        return jdbc.queryForObject("""
                SELECT COUNT(DISTINCT member.article_id) FROM story_snapshot_statistics_members member
                WHERE member.clustering_version_id = ?
                  AND member.snapshot_id = ANY(CAST((
                    SELECT ARRAY_AGG(run.snapshot_id) FROM story_processing_runs run
                    WHERE run.clustering_version_id = ? AND run.status = 'SUCCEEDED'
                      AND run.completed_at >= ? AND run.completed_at < ?
                  ) AS BIGINT ARRAY))
                """, Double.class, version, version, timestamp(from), timestamp(to));
    }

    private static Timestamp timestamp(String value) {
        return Timestamp.from(Instant.parse(value));
    }

    private static PGSimpleDataSource source(String url) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(System.getenv().getOrDefault("GEM128_JDBC_USER", "postgres"));
        source.setPassword(System.getenv().getOrDefault("GEM128_JDBC_PASSWORD", "gem128-local-only"));
        return source;
    }
}
