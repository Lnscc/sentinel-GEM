package com.example.globalnewsenginev1.statistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.Array;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;

/** Counts the union without expanding every archived snapshot into SQL window rows. */
final class ProcessedArticleCounter {
    private ProcessedArticleCounter() {}

    static double count(JdbcTemplate jdbc, long version, Timestamp from, Timestamp to) {
        Set<Long> articles = new HashSet<>();
        jdbc.query("""
                SELECT DISTINCT input.article_id
                FROM story_snapshot_members member
                JOIN story_article_inputs input ON input.id = member.article_input_id
                WHERE member.clustering_version_id = ?
                  AND NOT EXISTS (SELECT 1 FROM story_snapshot_retention archive
                                  WHERE archive.snapshot_id = member.snapshot_id)
                  AND EXISTS (SELECT 1 FROM story_processing_runs run
                              WHERE run.snapshot_id = member.snapshot_id AND run.clustering_version_id = ?
                                AND run.status = 'SUCCEEDED' AND run.completed_at >= ? AND run.completed_at < ?)
                """, (RowCallbackHandler) row -> {
                    StatisticsQueryBudget.checkCurrent();
                    articles.add(row.getLong(1));
                }, version, version, from, to);
        var snapshots = jdbc.queryForList("""
                SELECT archive.snapshot_id
                FROM story_snapshot_retention archive
                JOIN story_snapshots snapshot ON snapshot.id = archive.snapshot_id
                WHERE snapshot.clustering_version_id = ?
                  AND EXISTS (SELECT 1 FROM story_processing_runs run
                              WHERE run.snapshot_id = archive.snapshot_id AND run.clustering_version_id = ?
                                AND run.status = 'SUCCEEDED' AND run.completed_at >= ? AND run.completed_at < ?)
                """, Long.class, version, version, from, to);
        for (long snapshot : snapshots) {
            // Each array is its own cancellable statement with the remaining request budget.
            // PostgreSQL cursor fetches bypass Statement.cancel(), so do not stream cursor pages here.
            jdbc.query("SELECT article_id_deltas FROM story_snapshot_retention WHERE snapshot_id = ?",
                    (RowCallbackHandler) row -> {
                        Array array = row.getArray(1);
                        try {
                            long article = 0;
                            Object[] deltas = (Object[]) array.getArray();
                            for (int index = 0; index < deltas.length; index++) {
                                if ((index & 4095) == 0) StatisticsQueryBudget.checkCurrent();
                                article = Math.addExact(article, ((Number) deltas[index]).longValue());
                                articles.add(article);
                            }
                        } finally {
                            array.free();
                        }
                    }, snapshot);
        }
        StatisticsQueryBudget.checkCurrent();
        return articles.size();
    }
}
