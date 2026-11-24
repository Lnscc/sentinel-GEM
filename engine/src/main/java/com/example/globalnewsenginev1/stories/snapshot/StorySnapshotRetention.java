package com.example.globalnewsenginev1.stories.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "stories.snapshots.retention.enabled", havingValue = "true")
class StorySnapshotRetention {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorySnapshotRetention.class);
    private static final int RECENT_PUBLISHED_SNAPSHOTS = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Duration minimumAge;
    private final int batchSize;
    private final int maxBatches;

    StorySnapshotRetention(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${stories.snapshots.retention.minimum-age:PT30M}") Duration minimumAge,
            @Value("${stories.snapshots.retention.batch-size:10000}") int batchSize,
            @Value("${stories.snapshots.retention.max-batches-per-run:8}") int maxBatches
    ) {
        if (minimumAge.isNegative() || batchSize <= 0 || maxBatches <= 0) {
            throw new IllegalArgumentException("Invalid story snapshot retention settings");
        }
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.minimumAge = minimumAge;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    @Scheduled(
            initialDelayString = "${stories.snapshots.retention.initial-delay:PT2M}",
            fixedDelayString = "${stories.snapshots.retention.poll-interval:PT1M}"
    )
    void compactSnapshots() {
        Instant cutoff = Instant.now().minus(minimumAge);
        int remainingBatches = maxBatches;
        for (long version : jdbc.queryForList("SELECT id FROM story_clustering_versions ORDER BY id", Long.class)) {
            while (remainingBatches > 0) {
                Batch batch = compactBatch(version, cutoff);
                if (batch == null) break;
                remainingBatches--;
                LOGGER.info("Story snapshot retention: snapshot={} removedPairs={} removedMembers={} complete={}",
                        batch.snapshotId(), batch.removedPairs(), batch.removedMembers(), batch.complete());
            }
            if (remainingBatches == 0) break;
        }
    }

    Batch compactBatch(long version, Instant cutoff) {
        return transaction.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, version))) {
                return null;
            }
            Long snapshot = nextSnapshot(version, cutoff);
            if (snapshot == null) return null;
            archive(snapshot);
            jdbc.execute("SET LOCAL stories.snapshot_retention = 'on'");
            int pairs = deletePairs(snapshot);
            int members = deleteMembers(snapshot);
            boolean complete = pairs < batchSize && members < batchSize;
            if (complete) {
                jdbc.update("""
                        UPDATE story_snapshot_retention
                        SET completed_at = CURRENT_TIMESTAMP, kept_pair_ids = NULL
                        WHERE snapshot_id = ?
                        """, snapshot);
            }
            return new Batch(snapshot, pairs, members, complete);
        });
    }

    private Long nextSnapshot(long version, Instant cutoff) {
        return jdbc.query("""
                WITH recent AS (
                    SELECT id FROM story_publish_commits
                    WHERE clustering_version_id = ?
                    ORDER BY id DESC LIMIT ?
                ), releases AS (
                    SELECT basis.id
                    FROM story_clustering_version_status_history history
                    CROSS JOIN LATERAL (
                        SELECT commit.id FROM story_publish_commits commit
                        WHERE commit.clustering_version_id = history.clustering_version_id
                          AND commit.published_at <= history.changed_at
                        ORDER BY commit.id DESC LIMIT 1
                    ) basis
                    WHERE history.clustering_version_id = ? AND history.previous_status IS NOT NULL
                )
                SELECT run.snapshot_id
                FROM story_processing_runs run
                JOIN story_publish_commits commit ON commit.run_id = run.id
                LEFT JOIN story_snapshot_retention archive ON archive.snapshot_id = run.snapshot_id
                WHERE run.clustering_version_id = ? AND run.run_mode = 'INCREMENTAL'
                  AND run.status = 'SUCCEEDED' AND run.completed_at < ?
                  AND archive.completed_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM story_processing_runs other
                      WHERE other.snapshot_id = run.snapshot_id AND other.id <> run.id
                  )
                  AND NOT EXISTS (SELECT 1 FROM recent WHERE recent.id = commit.id)
                  AND NOT EXISTS (SELECT 1 FROM releases WHERE releases.id = commit.id)
                ORDER BY commit.id
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), version, RECENT_PUBLISHED_SNAPSHOTS,
                version, version, Timestamp.from(cutoff)).stream().findFirst().orElse(null);
    }

    private void archive(long snapshot) {
        // Keep exact stats plus the medoid comparison (or closest available pair) for each assignment.
        jdbc.update("""
                INSERT INTO story_snapshot_retention (snapshot_id, article_id_deltas, kept_pair_ids)
                SELECT ?, ARRAY(
                    SELECT input.article_id - LAG(input.article_id, 1, 0)
                        OVER (ORDER BY input.article_id)
                    FROM story_snapshot_members member
                    JOIN story_article_inputs input ON input.id = member.article_input_id
                    WHERE member.snapshot_id = ? ORDER BY input.article_id
                ), ARRAY(
                    SELECT DISTINCT evidence.id
                    FROM story_assignment_decisions assignment
                    CROSS JOIN LATERAL (
                        SELECT pair.id FROM story_pair_decisions pair
                        WHERE pair.snapshot_id = assignment.snapshot_id
                          AND (pair.left_article_input_id = assignment.article_input_id
                            OR pair.right_article_input_id = assignment.article_input_id)
                        ORDER BY (
                            CASE WHEN pair.left_article_input_id = assignment.article_input_id
                                THEN pair.right_article_ref ELSE pair.left_article_ref END
                            = assignment.best_comparison_article_ref
                        ) DESC NULLS LAST, pair.cosine_similarity DESC, pair.id
                        LIMIT 1
                    ) evidence
                    WHERE assignment.snapshot_id = ?
                )
                WHERE NOT EXISTS (SELECT 1 FROM story_snapshot_retention WHERE snapshot_id = ?)
                """, snapshot, snapshot, snapshot, snapshot);
    }

    private int deletePairs(long snapshot) {
        return jdbc.update("""
                DELETE FROM story_pair_decisions target
                WHERE target.id IN (
                    SELECT pair.id FROM story_pair_decisions pair
                    WHERE pair.snapshot_id = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM unnest((SELECT kept_pair_ids FROM story_snapshot_retention
                                                WHERE snapshot_id = ?)) kept(id)
                          WHERE kept.id = pair.id
                      )
                    LIMIT ?
                )
                """, snapshot, snapshot, batchSize);
    }

    private int deleteMembers(long snapshot) {
        return jdbc.update("""
                DELETE FROM story_snapshot_members target
                WHERE target.snapshot_id = ? AND target.article_input_id IN (
                    SELECT member.article_input_id FROM story_snapshot_members member
                    WHERE member.snapshot_id = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM story_assignment_decisions assignment
                          WHERE assignment.snapshot_id = member.snapshot_id
                            AND assignment.article_input_id = member.article_input_id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM story_pair_decisions pair
                          WHERE pair.snapshot_id = member.snapshot_id
                            AND pair.left_article_input_id = member.article_input_id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM story_pair_decisions pair
                          WHERE pair.snapshot_id = member.snapshot_id
                            AND pair.right_article_input_id = member.article_input_id
                      )
                    LIMIT ?
                )
                """, snapshot, snapshot, batchSize);
    }

    record Batch(long snapshotId, int removedPairs, int removedMembers, boolean complete) { }
}
