package com.example.globalnewsenginev1.statistics;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedArticleCounterTests {
    @Test
    void countsExactUnionAcrossLivePartialAndCompleteArchivesAndRunBoundaries() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:counter-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE story_snapshot_members(snapshot_id BIGINT, clustering_version_id BIGINT, article_input_id BIGINT)");
        jdbc.execute("CREATE TABLE story_article_inputs(id BIGINT, article_id BIGINT)");
        jdbc.execute("CREATE TABLE story_snapshots(id BIGINT, clustering_version_id BIGINT)");
        jdbc.execute("CREATE TABLE story_snapshot_retention(snapshot_id BIGINT, article_id_deltas BIGINT ARRAY, completed_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE story_processing_runs(snapshot_id BIGINT, clustering_version_id BIGINT, status TEXT, completed_at TIMESTAMP WITH TIME ZONE)");
        jdbc.update("INSERT INTO story_article_inputs VALUES (1, 10), (2, 20), (3, 99)");
        jdbc.update("INSERT INTO story_snapshots VALUES (1, 1), (2, 1), (3, 1), (4, 1), (5, 2)");
        jdbc.update("INSERT INTO story_snapshot_members VALUES (1, 1, 1), (1, 1, 2), (2, 1, 3), (4, 1, 3), (5, 2, 3)");
        jdbc.update("INSERT INTO story_snapshot_retention VALUES (2, ARRAY[20, 10], NULL), (3, ARRAY[10, 20, 10], CURRENT_TIMESTAMP), (5, ARRAY[99], CURRENT_TIMESTAMP)");
        jdbc.update("""
                INSERT INTO story_processing_runs VALUES
                (1, 1, 'SUCCEEDED', '2026-11-27T00:00:00Z'),
                (1, 1, 'SUCCEEDED', '2026-11-27T01:00:00Z'),
                (2, 1, 'SUCCEEDED', '2026-11-27T02:00:00Z'),
                (3, 1, 'SUCCEEDED', '2026-11-27T03:00:00Z'),
                (4, 1, 'FAILED', '2026-11-27T04:00:00Z'),
                (4, 1, 'SUCCEEDED', '2026-11-28T00:00:00Z'),
                (5, 2, 'SUCCEEDED', '2026-11-27T05:00:00Z')
                """);
        var from = Timestamp.from(Instant.parse("2026-11-27T00:00:00Z"));
        var to = Timestamp.from(Instant.parse("2026-11-28T00:00:00Z"));
        assertThat(ProcessedArticleCounter.count(jdbc, 1, from, to)).isEqualTo(4);
        assertThat(ProcessedArticleCounter.count(jdbc, 1, to,
                Timestamp.from(Instant.parse("2026-11-29T00:00:00Z")))).isEqualTo(1);
        assertThat(ProcessedArticleCounter.count(jdbc, 99, from, to)).isZero();
    }
}
