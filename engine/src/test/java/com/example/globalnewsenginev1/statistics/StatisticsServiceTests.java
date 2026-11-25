package com.example.globalnewsenginev1.statistics;

import com.example.globalnewsenginev1.stories.EmbeddingUsageMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StatisticsServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private JdbcTemplate jdbc;
    private StatisticsService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:statistics-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE articles(id BIGINT, domain TEXT, created_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE gdelt_pipeline_health_view(domain_rows BIGINT, pending_payload_rows BIGINT, open_processing_errors BIGINT)");
        jdbc.execute("CREATE TABLE story_clustering_versions(id BIGINT, version_key TEXT, status TEXT)");
        jdbc.execute("CREATE TABLE stories(id BIGINT, clustering_version_id BIGINT, state TEXT, member_count INTEGER)");
        jdbc.execute("CREATE TABLE story_memberships(story_id BIGINT, clustering_version_id BIGINT, article_id BIGINT, current_marker SMALLINT)");
        jdbc.execute("CREATE TABLE story_locations(story_id BIGINT, rank INTEGER, country_code TEXT)");
        jdbc.execute("""
                CREATE TABLE gdelt_import_files(id BIGINT, status TEXT, row_count BIGINT,
                    started_at TIMESTAMP WITH TIME ZONE, completed_at TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.execute("""
                CREATE TABLE story_processing_runs(id BIGINT, clustering_version_id BIGINT, snapshot_id BIGINT,
                    status TEXT, started_at TIMESTAMP WITH TIME ZONE, completed_at TIMESTAMP WITH TIME ZONE)
                """);
        jdbc.execute("CREATE TABLE story_snapshot_statistics_members(snapshot_id BIGINT, clustering_version_id BIGINT, article_id BIGINT)");
        jdbc.execute("CREATE TABLE story_article_inputs(clustering_version_id BIGINT, current_marker SMALLINT, embedding_status TEXT, attempt_count INTEGER)");
        service = new StatisticsService(jdbc, new EmbeddingUsageMetrics(new SimpleMeterRegistry()), "visible",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void countsCurrentInventoryAndPeriodWorkWithoutMixingVersionsOrBoundaryTimes() {
        jdbc.update("INSERT INTO story_clustering_versions VALUES (1, 'visible', 'ACTIVE'), (2, 'other', 'SHADOW')");
        jdbc.update("INSERT INTO articles VALUES (1, 'one.test', '2026-11-17T12:00:00Z'), (2, 'one.test', '2026-11-18T11:00:00Z'), (3, 'two.test', '2026-11-18T12:00:00Z')");
        jdbc.update("INSERT INTO gdelt_pipeline_health_view VALUES (10, 3, 2), (20, 0, 1)");
        jdbc.update("INSERT INTO stories VALUES (1, 1, 'ACTIVE', 1), (2, 1, 'CLOSED', 3), (3, 1, 'ACTIVE', 8), (4, 1, 'ACTIVE', 30), (5, 1, 'SUPERSEDED', 1), (6, 2, 'ACTIVE', 1)");
        jdbc.update("INSERT INTO story_locations VALUES (1, 1, 'GM'), (2, 1, 'GM'), (3, 1, 'FR'), (3, 2, 'US'), (5, 1, 'UK'), (6, 1, 'IT')");
        jdbc.update("INSERT INTO story_memberships VALUES (1, 1, 1, 1), (2, 1, 2, 1), (5, 1, 3, 1), (6, 2, 3, 1), (2, 1, 3, NULL)");
        jdbc.update("INSERT INTO gdelt_import_files VALUES (1, 'COMPLETED', 100, '2026-11-18T10:00:00Z', '2026-11-18T10:00:05Z'), (2, 'FAILED', 0, '2026-11-18T11:00:00Z', '2026-11-18T11:00:02Z'), (3, 'COMPLETED', 500, '2026-11-18T11:00:00Z', '2026-11-18T12:00:00Z')");
        jdbc.update("INSERT INTO story_processing_runs VALUES (1, 1, 10, 'SUCCEEDED', '2026-11-18T10:00:00Z', '2026-11-18T10:00:03Z'), (2, 1, 11, 'SUCCEEDED', '2026-11-18T10:01:00Z', '2026-11-18T10:01:02Z'), (3, 2, 20, 'SUCCEEDED', '2026-11-18T11:00:00Z', '2026-11-18T11:00:04Z')");
        jdbc.update("INSERT INTO story_snapshot_statistics_members VALUES (10, 1, 1), (10, 1, 2), (11, 1, 1), (20, 2, 3)");
        jdbc.update("INSERT INTO story_article_inputs VALUES (1, 1, 'PENDING', 0), (1, 1, 'RETRYABLE_FAILURE', 4), (1, 1, 'RETRYABLE_FAILURE', 5), (1, 1, 'TERMINAL_FAILURE', 1), (2, 1, 'PENDING', 0)");

        StatisticsReport report = service.report(null, null);

        assertThat(report.visibleVersion()).isEqualTo("visible");
        assertThat(report.visibleVersionStatus()).isEqualTo("ACTIVE");
        assertValue(report, "articles", 3);
        assertValue(report, "sources", 2);
        assertValue(report, "signals", 30);
        assertValue(report, "pendingPayloads", 3);
        assertValue(report, "processingErrors", 3);
        assertValue(report, "stories", 4);
        assertValue(report, "countries", 2);
        assertValue(report, "assignedArticles", 2);
        for (String id : new String[]{"singletonStories", "smallStories", "mediumStories", "largeStories"}) {
            assertValue(report, id, 1);
        }
        assertValue(report, "incomingArticles", 2);
        assertValue(report, "processedArticles", 2);
        assertValue(report, "processingThroughput", 2 / 24.0);
        assertValue(report, "completedImports", 1);
        assertValue(report, "failedImports", 1);
        assertValue(report, "importedRows", 100);
        assertValue(report, "importDuration", 7);
        assertValue(report, "successfulStoryRuns", 2);
        assertValue(report, "storyDuration", 5);
        assertValue(report, "pendingEmbeddings", 2);
        assertValue(report, "failedEmbeddings", 2);
        assertThat(report.recentRuns().stream().filter(run -> run.kind().equals("STORY"))).hasSize(2);
        assertThat(metric(report, "incomingArticles").from()).isEqualTo(NOW.minusSeconds(86400));
        assertThat(metric(report, "articles").from()).isNull();
        assertThat(metric(report, "articles").to()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHADOW", "RETIRED", "UNKNOWN"})
    void hidesStoryDataWhenConfiguredVersionIsNotActive(String status) {
        if (!status.equals("UNKNOWN")) {
            jdbc.update("INSERT INTO story_clustering_versions VALUES (1, 'visible', ?)", status);
        }
        jdbc.update("INSERT INTO story_clustering_versions VALUES (2, 'other', 'ACTIVE')");
        jdbc.update("INSERT INTO articles VALUES (1, 'one.test', '2026-11-18T11:00:00Z')");
        jdbc.update("INSERT INTO stories VALUES (1, 1, 'ACTIVE', 1), (2, 2, 'ACTIVE', 1)");
        jdbc.update("INSERT INTO story_locations VALUES (1, 1, 'GM'), (2, 1, 'FR')");
        jdbc.update("INSERT INTO story_memberships VALUES (1, 1, 1, 1), (2, 2, 1, 1)");
        jdbc.update("INSERT INTO story_processing_runs VALUES (1, 1, 10, 'SUCCEEDED', '2026-11-18T10:00:00Z', '2026-11-18T10:00:03Z'), (2, 2, 20, 'SUCCEEDED', '2026-11-18T11:00:00Z', '2026-11-18T11:00:04Z')");
        jdbc.update("INSERT INTO story_snapshot_statistics_members VALUES (10, 1, 1), (20, 2, 1)");
        jdbc.update("INSERT INTO story_article_inputs VALUES (1, 1, 'PENDING', 0), (1, 1, 'TERMINAL_FAILURE', 1)");
        jdbc.update("INSERT INTO gdelt_import_files VALUES (1, 'COMPLETED', 100, '2026-11-18T10:00:00Z', '2026-11-18T10:00:05Z')");

        StatisticsReport report = service.report(null, null);

        assertThat(report.visibleVersion()).isEqualTo("visible");
        assertThat(report.visibleVersionStatus()).isEqualTo(status.equals("UNKNOWN") ? null : status);
        for (String id : new String[]{"stories", "singletonStories", "smallStories", "mediumStories",
                "largeStories", "countries", "assignedArticles", "processedArticles", "processingThroughput",
                "successfulStoryRuns", "failedStoryRuns", "storyDuration", "runningStoryRuns",
                "pendingStoryRuns", "pendingEmbeddings", "failedEmbeddings"}) {
            assertThat(metric(report, id).value()).as(id).isNull();
            assertThat(metric(report, id).unavailableReason()).as(id).contains("nicht ACTIVE");
        }
        assertValue(report, "articles", 1);
        assertValue(report, "incomingArticles", 1);
        assertValue(report, "completedImports", 1);
        assertThat(report.recentRuns()).singleElement().satisfies(run -> assertThat(run.kind()).isEqualTo("IMPORT"));
    }

    @Test
    void distinguishesAbsentMeasurementsFromMeasuredZeroAndMissingVersion() {
        StatisticsReport missing = service.report(null, null);
        assertValue(missing, "articles", 0);
        assertThat(metric(missing, "stories").value()).isNull();
        assertThat(metric(missing, "stories").unavailableReason()).isNotBlank();
        assertThat(missing.visibleVersionStatus()).isNull();
        jdbc.update("INSERT INTO story_clustering_versions VALUES (1, 'visible', 'ACTIVE')");
        StatisticsReport empty = service.report(null, null);
        assertValue(empty, "stories", 0);
        assertValue(empty, "processedArticles", 0);
        assertValue(empty, "embeddingCalls", 0);
        for (String id : new String[]{"storyDuration", "importDuration", "embeddingDuration", "embeddingTokens", "estimatedEmbeddingCost"}) {
            assertThat(metric(empty, id).value()).as(id).isNull();
            assertThat(metric(empty, id).unavailableReason()).as(id).isNotBlank();
        }
    }

    @Test
    void relativePeriodUsesServerClock() {
        var report = service.recent(168);
        assertThat(report.period().to()).isEqualTo(NOW);
        assertThat(report.period().from()).isEqualTo(NOW.minusSeconds(168 * 3600));
        assertThatIllegalArgumentException().isThrownBy(() -> service.recent(0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.recent(745));
    }

    @Test
    void rejectsInvalidUnboundedAndFuturePeriods() {
        for (String[] period : new String[][]{
                {"not-a-time", null}, {"2026-11-18T11:00:00", null},
                {null, "2026-11-18T12:01:00Z"}, {"2026-11-18T12:00:00Z", null},
                {"2026-10-01T00:00:00Z", null}, {null, "0001-01-01T00:00:00Z"}
        }) {
            assertThatIllegalArgumentException().isThrownBy(() -> service.report(period[0], period[1]));
        }
    }

    private static StatisticsReport.Metric metric(StatisticsReport report, String id) {
        return report.metrics().stream().filter(metric -> metric.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertValue(StatisticsReport report, String id, double expected) {
        assertThat(metric(report, id).value()).as(id).isEqualTo(expected);
    }
}
