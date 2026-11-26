package com.example.globalnewsenginev1.stories.snapshot;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StoryProjectionScopePostgresIT {
    private static final String VERSION = "story-mvp-title-embedding-24h-v1.1.0";
    private static final int AFFECTED = 65_536;
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    void publishesLargeSourceOnlyScopeAtomicallyWithoutChangingMemberships() throws Exception {
        String url = System.getProperty("it.postgres.jdbc-url", "jdbc:postgresql://localhost:5432/gne");
        var adminSource = source(url);
        try (var connection = adminSource.getConnection()) {
            assumeTrue(connection.isValid(2), "Requires the local PostgreSQL integration database");
        } catch (java.sql.SQLException exception) {
            assumeTrue(false, "Requires the local PostgreSQL integration database");
        }
        String schema = "projection_scope_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(adminSource);
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource = source(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        var jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        var transaction = new TransactionTemplate(manager);
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).load().migrate();
            var repository = new StorySnapshotRepository(jdbc);
            transaction.executeWithoutResult(status -> seed(jdbc, repository));
            jdbc.execute("ANALYZE");
            var version = repository.findActiveVersion().getFirst();
            Instant publishedAt = NOW.plusSeconds(300);
            var inputs = repository.findSnapshotInputs(version.id(), publishedAt);
            String hash = StorySnapshotCanonicalizer.snapshotInputHash(VERSION, publishedAt, inputs);
            var snapshot = transaction.execute(status -> repository.ensureSnapshot(version, publishedAt,
                    StorySnapshotCanonicalizer.snapshotKey(VERSION, publishedAt, hash), hash, inputs, publishedAt));
            var run = transaction.execute(status -> repository.claimRun(version, snapshot,
                    StorySnapshotService.RunMode.INCREMENTAL,
                    StorySnapshotCanonicalizer.runKey(VERSION, hash, StorySnapshotService.RunMode.INCREMENTAL,
                            version.pairRuleVersion()), publishedAt, Duration.ofMinutes(30)).orElseThrow());
            var partition = new StoryPartitionService.Partition(repository.loadPartitionRules(snapshot.id()),
                    List.of(), List.of());
            var publisher = new StoryPublisher(jdbc, repository, manager, new SimpleMeterRegistry(),
                    new StoryLocationMaterializer(jdbc));
            var plan = publisher.prepare(snapshot, partition, Set.of());
            var failingPublisher = new StoryPublisher(jdbc, repository, manager, new SimpleMeterRegistry(),
                    new StoryLocationMaterializer(jdbc) {
                        @Override
                        void refresh(long versionId, Set<UUID> storyIds) {
                            super.refresh(versionId, storyIds);
                            assertThat(storyIds).hasSize(AFFECTED);
                            throw new IllegalStateException("Injected projection failure");
                        }
                    });
            assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                    failingPublisher.publish(plan, run, publishedAt, Duration.ofMinutes(30))))
                    .hasMessageContaining("Injected projection failure");
            assertThat(count(jdbc, "SELECT COUNT(*) FROM story_projection_dirty_sources")).isEqualTo(AFFECTED);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM stories WHERE member_count = 0")).isEqualTo(AFFECTED + 1);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM story_locations")).isEqualTo(AFFECTED + 1);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM story_publish_commits")).isZero();
            var result = transaction.execute(status -> {
                var published = publisher.publish(plan, run, publishedAt, Duration.ofMinutes(30));
                publisher.assertLease(version.id(), run, publishedAt, Duration.ofMinutes(30));
                return published;
            });
            assertThat(result.outcome()).isEqualTo("PUBLISHED");
            assertThat(result.changed()).isZero();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM story_projection_dirty_sources")).isZero();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM stories WHERE member_count = 1 AND source_count = 1 AND independent_report_count = 1"))
                    .isEqualTo(AFFECTED);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM stories WHERE member_count = 0")).isOne();
            assertThat(jdbc.queryForObject("SELECT name FROM story_locations", String.class)).isEqualTo("untouched");
            assertThat(count(jdbc, "SELECT COUNT(*) FROM story_publish_commits")).isOne();
            assertThat(count(jdbc, "SELECT COUNT(*) FROM story_memberships WHERE current_marker = 1")).isEqualTo(AFFECTED + 1);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM story_assignment_decisions")).isEqualTo(AFFECTED + 1);
            assertThat(count(jdbc, """
                    SELECT COUNT(*) FROM stories story JOIN story_memberships member ON member.story_id = story.id
                    WHERE story.id = md5(member.article_ref)::uuid AND story.public_id = story.id
                        AND story.optimistic_version = 0 AND story.state = 'CLOSED'
                    """)).isEqualTo(AFFECTED + 1);
            // Exercise the sibling text/UUID filters without doing any pair comparisons or assignment writes.
            Set<String> refs = inputs.stream().map(StorySnapshotRepository.SnapshotInput::articleRef)
                    .collect(Collectors.toSet());
            var largeBasis = publisher.prepare(snapshot, partition, refs);
            assertThat(largeBasis.basis().stories()).hasSize(AFFECTED + 1);
            assertThat(largeBasis.basis().memberships()).hasSize(AFFECTED + 1);
            assertThat(largeBasis.inputs()).hasSize(AFFECTED + 1);
            Set<UUID> largeScope = largeBasis.basis().stories().stream().map(StoryPublisher.Story::id)
                    .collect(Collectors.toSet());
            transaction.executeWithoutResult(status -> ReflectionTestUtils.invokeMethod(publisher,
                    "closeUnchangedStories", snapshot, run, publishedAt, largeScope,
                    new java.util.HashMap<String, Long>()));
            assertThat(transaction.execute(status -> publisher.publish(plan, run, publishedAt, Duration.ofMinutes(30))).outcome())
                    .isEqualTo("REUSED");
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void seed(JdbcTemplate jdbc, StorySnapshotRepository repository) {
        jdbc.execute("ALTER TABLE story_clustering_versions DISABLE TRIGGER USER");
        jdbc.update("UPDATE story_clustering_versions SET status = CASE WHEN version_key = ? THEN 'ACTIVE' ELSE 'SHADOW' END", VERSION);
        jdbc.execute("ALTER TABLE story_clustering_versions ENABLE TRIGGER USER");
        var version = repository.findActiveVersion().getFirst();
        byte[] vector = ByteBuffer.allocate(1536 * Float.BYTES).putFloat(1).array();
        long artifact = jdbc.queryForObject("""
                INSERT INTO story_embedding_artifacts (embedding_model_id, embedding_model_version,
                    embedding_dimension, title_normalization_version, title_input_hash, status,
                    vector_bytes, vector_hash, vector_norm, attempt_count, ready_at, created_at, updated_at)
                VALUES ('text-embedding-3-small', 'openai:text-embedding-3-small@2026-07-20',
                    1536, 'art031-title-nfkc-ws-v1', repeat('a', 64), 'READY', ?, ?, 1, 1, ?, ?, ?) RETURNING id
                """, Long.class, vector, StorySnapshotCanonicalizer.sha256(vector), ts(NOW), ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO articles (canonical_url, url_hash, domain, first_seen_at, created_at, updated_at)
                SELECT 'https://projection.example/' || n, repeat(md5('projection:' || n), 2),
                    'projection.example', ?, ?, ? FROM generate_series(1, ?) n
                """, ts(NOW), ts(NOW), ts(NOW), AFFECTED + 1);
        jdbc.update("""
                INSERT INTO story_article_inputs (clustering_version_id, article_id, article_ref,
                    effective_at, effective_at_source, normalized_title, title_input_hash, title_usability,
                    article_input_fingerprint, embedding_status, embedding_artifact_id,
                    attempt_count, current_marker, created_at)
                SELECT ?, id, url_hash, ?, 'PUBLISHED_AT', 'Projection fixture', repeat('a', 64), 'USABLE',
                    repeat(md5(url_hash), 2), 'READY', ?, 1, 1, ? FROM articles
                """, version.id(), ts(NOW), artifact, ts(NOW));
        var inputs = repository.findSnapshotInputs(version.id(), NOW);
        String hash = StorySnapshotCanonicalizer.snapshotInputHash(VERSION, NOW, inputs);
        var snapshot = repository.ensureSnapshot(version, NOW,
                StorySnapshotCanonicalizer.snapshotKey(VERSION, NOW, hash), hash, inputs, NOW);
        var run = repository.claimRun(version, snapshot, StorySnapshotService.RunMode.BACKFILL,
                StorySnapshotCanonicalizer.runKey(VERSION, hash, StorySnapshotService.RunMode.BACKFILL,
                        version.pairRuleVersion()), NOW, Duration.ofMinutes(30)).orElseThrow();
        jdbc.update("""
                INSERT INTO stories (id, public_id, clustering_version_id, identity_anchor_article_ref,
                    representative_article_ref, state, effective_from, effective_to,
                    created_by_run_id, last_changed_by_run_id, created_at, updated_at, closed_at)
                SELECT md5(article_ref)::uuid, md5(article_ref)::uuid, clustering_version_id, article_ref,
                    article_ref, 'CLOSED', effective_at, effective_at, ?, ?, ?, ?, ? FROM story_article_inputs
                """, run.id(), run.id(), ts(NOW), ts(NOW), ts(NOW));
        jdbc.update("""
                INSERT INTO story_assignment_decisions (decision_hash, clustering_version_id, run_id,
                    snapshot_id, article_input_id, article_ref, article_input_fingerprint,
                    resulting_story_id, result, assignment_reason, component_rule_version,
                    best_comparison_article_ref, best_cosine_similarity, created_at)
                SELECT repeat(md5('decision:' || article_ref), 2), clustering_version_id, ?, ?, id,
                    article_ref, article_input_fingerprint, md5(article_ref)::uuid, 'ASSIGNED',
                    'MEDOID_RADIUS', ?, article_ref, 1, ? FROM story_article_inputs
                """, run.id(), snapshot.id(), repository.loadPartitionRules(snapshot.id()).componentRuleVersion(), ts(NOW));
        jdbc.update("""
                INSERT INTO story_memberships (story_id, clustering_version_id, article_id, article_ref,
                    article_input_fingerprint, valid_from_run_id, decision_id, assignment_reason,
                    current_marker, created_at)
                SELECT resulting_story_id, decision.clustering_version_id, input.article_id, decision.article_ref,
                    decision.article_input_fingerprint, ?, decision.id, 'MEDOID_RADIUS', 1, ?
                FROM story_assignment_decisions decision JOIN story_article_inputs input ON input.id = decision.article_input_id
                """, run.id(), ts(NOW));
        jdbc.update("""
                INSERT INTO story_locations (story_id, rank, name, latitude, longitude, source,
                    confidence, supporting_articles, supporting_domains)
                SELECT id, 1, CASE WHEN representative_article_ref = repeat(md5('projection:' || ?), 2)
                    THEN 'untouched' ELSE 'stale' END, 0, 0, 'GDELT_EVENT_ACTION', 100, 1, 1 FROM stories
                """, AFFECTED + 1);
        jdbc.update("DELETE FROM story_projection_dirty_sources WHERE article_id = (SELECT id FROM articles WHERE url_hash = repeat(md5('projection:' || ?), 2))", AFFECTED + 1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM story_projection_dirty_sources")).isEqualTo(AFFECTED);
        repository.completeRun(run, AFFECTED + 1, AFFECTED + 1, 0, 0, NOW);
    }

    private long count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private PGSimpleDataSource source(String url) {
        var source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(System.getProperty("it.postgres.username", "gne"));
        source.setPassword(System.getProperty("it.postgres.password", "gne"));
        return source;
    }
}
