package com.example.globalnewsenginev1.stories.snapshot;

import com.example.globalnewsenginev1.GlobalNewsEngineV1Application;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in storage acceptance; synthetic time distribution deliberately does not establish GEM-124 throughput. */
class StorySnapshotRetentionScalePostgresIT {
    private static final int INPUTS = 107_000;
    private static final String VERSION = "story-mvp-title-embedding-24h-v1.1.0";
    private static final Instant INITIAL = Instant.parse("2026-11-01T12:00:00Z");
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private JdbcTemplate jdbc;
    private PGSimpleDataSource dataSource;
    private long version;
    private long artifact;
    private long baselineRun;

    @Test
    void boundsRepeatedSnapshotStorageWith107000ReadyInputsAndReachableApis() throws Exception {
        assumeTrue(Boolean.getBoolean("it.retention.scale"), "Enable explicitly with -Dit.retention.scale=true");
        String url = System.getProperty("it.postgres.jdbc-url", "jdbc:postgresql://localhost:55435/gne");
        assertThat(url).as("Scale fixture must use a dedicated local PostgreSQL database")
                .matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):55435/[a-zA-Z0-9_]+(?:\\?.*)?");
        String schema = "retention_scale_" + UUID.randomUUID().toString().replace("-", "");
        PGSimpleDataSource admin = source(url);
        JdbcTemplate administration = new JdbcTemplate(admin);
        administration.execute("CREATE SCHEMA " + schema);
        dataSource = source(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        jdbc = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).load().migrate();
            seed();
            sample("seed", 0);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicBoolean cleaning = new AtomicBoolean();
            try (var application = application(schema); var client = HttpClient.newHttpClient();
                 var executor = Executors.newSingleThreadExecutor()) {
                int port = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
                var probe = executor.submit(() -> probe(client, port, stop, cleaning));
                try {
                    for (int cycle = 1; cycle <= 8; cycle++) {
                        Instant now = INITIAL.plusSeconds(cycle * 300L);
                        int first = cycle == 1 ? INPUTS + 1 : INPUTS + 1000 + (cycle - 2) * 8 + 1;
                        int last = cycle == 1 ? INPUTS + 1000 : INPUTS + 1000 + (cycle - 1) * 8;
                        addInputs(first, last, now, false);
                        replaceInputs(cycle, now);
                        long started = System.nanoTime();
                        assertThat(service(now).processIncremental(1).succeededVersions()).isOne();
                        sample("published-" + cycle, elapsed(started));
                        cleaning.set(true);
                        try {
                            compact(now.plusSeconds(1), cycle);
                        } finally {
                            cleaning.set(false);
                        }
                        assertSnapshotStatistics();
                        sample("compacted-" + cycle, 0);
                        jdbc.execute("VACUUM (ANALYZE) story_snapshot_members, story_pair_decisions");
                        sample("vacuumed-" + cycle, 0);
                    }
                } finally {
                    stop.set(true);
                    probe.get(20, TimeUnit.SECONDS);
                }
            }
            assertThat(requests).isNotEmpty().allSatisfy(request ->
                    assertThat(request.get("status")).isEqualTo(200));
            assertThat(requests).anySatisfy(request -> assertThat(request.get("duringCleanup")).isEqualTo(true));
            assertThat(count("SELECT COUNT(*) FROM story_memberships WHERE current_marker = 1"))
                    .isEqualTo(INPUTS + 1056);
            assertThat(count("""
                    SELECT COUNT(*) FROM story_memberships member
                    JOIN story_article_inputs input ON input.article_ref = member.article_ref
                        AND input.article_input_fingerprint = member.article_input_fingerprint
                    WHERE member.current_marker = 1 AND input.current_marker = 1
                    """)).isEqualTo(INPUTS + 1056);
            assertThat(count("SELECT COUNT(*) FROM story_snapshot_retention WHERE completed_at IS NOT NULL"))
                    .isEqualTo(5);
            assertThat(count("SELECT COUNT(*) FROM story_snapshot_retention WHERE completed_at IS NULL")).isZero();
            assertThat(count("SELECT COUNT(*) FROM story_snapshot_members"))
                    .as("Protected baseline plus three recent complete copies and bounded decision evidence")
                    .isLessThan(5L * INPUTS);
            assertThat(count("SELECT COUNT(*) FROM story_snapshot_members WHERE snapshot_id = 1")).isEqualTo(INPUTS);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM story_memberships WHERE current_marker = 1 AND valid_from_run_id = ?
                    """, Long.class, baselineRun)).as("Unchanged logical memberships retain their original evidence")
                    .isEqualTo(INPUTS - 4);
        } finally {
            try {
                writeReport();
            } finally {
                administration.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private void seed() {
        jdbc.execute("ALTER TABLE story_clustering_versions DISABLE TRIGGER USER");
        jdbc.update("UPDATE story_clustering_versions SET status = CASE WHEN version_key = ? THEN 'ACTIVE' ELSE 'SHADOW' END", VERSION);
        jdbc.execute("ALTER TABLE story_clustering_versions ENABLE TRIGGER USER");
        version = jdbc.queryForObject("SELECT id FROM story_clustering_versions WHERE version_key = ?", Long.class, VERSION);
        byte[] vector = ByteBuffer.allocate(1536 * Float.BYTES).putFloat(1).array();
        artifact = jdbc.queryForObject("""
                INSERT INTO story_embedding_artifacts (embedding_model_id, embedding_model_version,
                    embedding_dimension, title_normalization_version, title_input_hash, status,
                    vector_bytes, vector_hash, vector_norm, attempt_count, ready_at, created_at, updated_at)
                VALUES ('text-embedding-3-small', 'openai:text-embedding-3-small@2026-07-20',
                    1536, 'art031-title-nfkc-ws-v1', repeat('a', 64), 'READY', ?, ?, 1, 1, ?, ?, ?) RETURNING id
                """, Long.class, vector, StorySnapshotCanonicalizer.sha256(vector), ts(INITIAL), ts(INITIAL), ts(INITIAL));
        addInputs(1, INPUTS, INITIAL, true);
        var repository = new StorySnapshotRepository(jdbc);
        var rules = repository.findActiveVersion().getFirst();
        var inputs = repository.findSnapshotInputs(version, INITIAL);
        assertThat(inputs).hasSize(INPUTS);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            String hash = StorySnapshotCanonicalizer.snapshotInputHash(VERSION, INITIAL, inputs);
            var snapshot = repository.ensureSnapshot(rules, INITIAL,
                    StorySnapshotCanonicalizer.snapshotKey(VERSION, INITIAL, hash), hash, inputs, INITIAL);
            var run = repository.claimRun(rules, snapshot, StorySnapshotService.RunMode.BACKFILL,
                    StorySnapshotCanonicalizer.runKey(VERSION, hash, StorySnapshotService.RunMode.BACKFILL,
                            rules.pairRuleVersion()), INITIAL, Duration.ofMinutes(30)).orElseThrow();
            baselineRun = run.id();
            // Mature independent history is already closed; 107k simultaneous state transitions are a separate load.
            jdbc.update("""
                    INSERT INTO stories (id, public_id, clustering_version_id, identity_anchor_article_ref,
                        representative_article_ref, state, effective_from, effective_to,
                        created_by_run_id, last_changed_by_run_id, created_at, updated_at,
                        representative_article_id, representative_canonical_url, representative_domain,
                        representative_first_seen_at, representative_title, member_count,
                        source_count, independent_report_count, closed_at)
                    SELECT md5(input.article_ref)::uuid, md5(input.article_ref)::uuid, input.clustering_version_id,
                        input.article_ref, input.article_ref, 'CLOSED', input.effective_at, input.effective_at,
                        ?, ?, ?, ?, article.id, article.canonical_url, article.domain, article.first_seen_at,
                        NULL, 1, 1, 1, ?
                    FROM story_article_inputs input JOIN articles article ON article.id = input.article_id
                    """, run.id(), run.id(), ts(INITIAL), ts(INITIAL), ts(INITIAL));
            jdbc.update("""
                    INSERT INTO story_assignment_decisions (decision_hash, clustering_version_id, run_id,
                        snapshot_id, article_input_id, article_ref, article_input_fingerprint,
                        resulting_story_id, result, assignment_reason, component_rule_version,
                        best_comparison_article_ref, best_cosine_similarity, created_at)
                    SELECT repeat(md5('decision:' || article_ref), 2), clustering_version_id, ?, ?, id,
                        article_ref, article_input_fingerprint, md5(article_ref)::uuid, 'ASSIGNED',
                        'MEDOID_RADIUS', ?, article_ref, 1, ? FROM story_article_inputs
                    """, run.id(), snapshot.id(), repository.loadPartitionRules(snapshot.id()).componentRuleVersion(), ts(INITIAL));
            jdbc.update("""
                    INSERT INTO story_memberships (story_id, clustering_version_id, article_id, article_ref,
                        article_input_fingerprint, valid_from_run_id, decision_id, assignment_reason,
                        current_marker, created_at)
                    SELECT decision.resulting_story_id, input.clustering_version_id, input.article_id,
                        input.article_ref, input.article_input_fingerprint, ?, decision.id,
                        'MEDOID_RADIUS', 1, ?
                    FROM story_article_inputs input JOIN story_assignment_decisions decision
                        ON decision.article_input_id = input.id
                    """, run.id(), ts(INITIAL));
            jdbc.update("""
                    INSERT INTO story_publish_commits (publish_key, clustering_version_id, snapshot_id,
                        run_id, fencing_token, published_at) VALUES (repeat('b', 64), ?, ?, ?, ?, ?)
                    """, version, snapshot.id(), run.id(), run.fencingToken(), ts(INITIAL));
            repository.completeRun(run, INPUTS, INPUTS, 0, 0, INITIAL);
            // The bulk baseline above also materializes publication projections. Consume its article
            // notifications in the same seed transaction, as a real publisher would after refreshing them.
            assertThat(jdbc.update("""
                    DELETE FROM story_projection_dirty_sources dirty USING articles article
                    WHERE dirty.article_id = article.id AND dirty.source_key = 'a:' || article.id
                    """)).isEqualTo(INPUTS);
            assertThat(count("SELECT COUNT(*) FROM story_projection_dirty_sources")).isZero();
        });
        jdbc.execute("ANALYZE");
    }

    private void addInputs(int first, int last, Instant now, boolean old) {
        jdbc.update("""
                INSERT INTO articles (canonical_url, url_hash, domain, first_seen_at, created_at, updated_at)
                SELECT 'https://scale.example/' || n, repeat(md5('article:' || n), 2),
                    'scale.example', ?, ?, ? FROM generate_series(?, ?) n
                """, ts(now), ts(now), ts(now), first, last);
        jdbc.update("""
                INSERT INTO story_article_inputs (clustering_version_id, article_id, article_ref,
                    effective_at, effective_at_source, normalized_title, title_input_hash, title_usability,
                    article_input_fingerprint, embedding_status, embedding_artifact_id,
                    attempt_count, current_marker, created_at)
                SELECT ?, article.id, article.url_hash,
                    ?::timestamptz - CASE WHEN ? THEN (n * interval '25 hours') ELSE interval '0' END,
                    'PUBLISHED_AT', 'Synthetic scale title', repeat('a', 64), 'USABLE',
                    repeat(md5('input:' || n), 2), 'READY', ?, 1, 1, ?
                FROM generate_series(?, ?) n
                JOIN articles article ON article.url_hash = repeat(md5('article:' || n), 2)
                """, version, ts(now), old, artifact, ts(now), first, last);
    }

    private void replaceInputs(int cycle, Instant now) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE story_article_inputs SET current_marker = NULL, superseded_at = ?
                    WHERE article_ref IN (SELECT repeat(md5('article:' || n), 2)
                        FROM generate_series(1, 4) n
                        UNION ALL SELECT repeat(md5('article:' || n), 2)
                        FROM generate_series(107001, 108000) n WHERE ? > 1) AND current_marker = 1
                    """, ts(now), cycle);
            jdbc.update("""
                    INSERT INTO story_article_inputs (clustering_version_id, article_id, article_ref,
                        effective_at, effective_at_source, normalized_title, title_input_hash, title_usability,
                        article_input_fingerprint, embedding_status, embedding_artifact_id,
                        attempt_count, current_marker, created_at)
                    SELECT clustering_version_id, article_id, article_ref, effective_at,
                        effective_at_source, normalized_title, title_input_hash, title_usability,
                        repeat(md5(article_ref || ':' || ?), 2), 'READY', embedding_artifact_id, 1, 1, ?
                    FROM story_article_inputs WHERE superseded_at = ?
                    """, cycle, ts(now), ts(now));
        });
    }

    private StorySnapshotService service(Instant now) {
        var repository = new StorySnapshotRepository(jdbc);
        var transactions = new DataSourceTransactionManager(dataSource);
        return new StorySnapshotService(repository, transactions, Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(30), new StorySnapshotMetrics(meters),
                new StoryPublisher(jdbc, repository, transactions, meters, new StoryLocationMaterializer(jdbc)));
    }

    private void compact(Instant cutoff, int cycle) {
        int batches = 0;
        long maximumBatchMillis = 0;
        long maximumArchiveMillis = 0;
        // Five one-minute polls allow at most 5 * 8 default batches per five-minute input cycle.
        // Exercise that allowance without sleeping; this measures bounded work, not scheduler wall time.
        for (int index = 0; index < 40; index++) {
            // Reconstruct each worker: all progress must survive a service/process restart.
            var retention = new StorySnapshotRetention(jdbc, new DataSourceTransactionManager(dataSource),
                    Duration.ZERO, 10_000, 8, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(5));
            long started = System.nanoTime();
            var batch = retention.compactBatch(version, cutoff);
            long batchMillis = elapsed(started);
            if (batch == null) break;
            batches++;
            maximumBatchMillis = Math.max(maximumBatchMillis, batchMillis);
            maximumArchiveMillis = Math.max(maximumArchiveMillis, batch.archiveMillis());
            assertThat(batch.removedPairs()).isBetween(0, 10_000);
            assertThat(batch.removedMembers()).isBetween(0, 10_000);
            assertThat(batch.archiveMillis()).as("Archive preparation remains within the statement budget").isLessThan(2_000);
            assertThat(batchMillis).as("Version lock and transaction including commit remain within the transaction budget").isLessThan(5_000);
            samples.add(Map.of("phase", "cleanup-" + cycle, "milliseconds", batchMillis,
                    "archiveMilliseconds", batch.archiveMillis(),
                    "poll", index / 8 + 1, "snapshot", batch.snapshotId(), "removedPairs", batch.removedPairs(),
                    "removedMembers", batch.removedMembers(), "complete", batch.complete()));
        }
        long backlog = jdbc.queryForObject("""
                SELECT COUNT(*) FROM story_processing_runs run
                JOIN story_publish_commits commit ON commit.run_id = run.id
                WHERE run.clustering_version_id = ? AND run.run_mode = 'INCREMENTAL'
                    AND run.status = 'SUCCEEDED' AND run.completed_at < ?
                    AND NOT EXISTS (SELECT 1 FROM story_snapshot_retention archive
                        WHERE archive.snapshot_id = run.snapshot_id AND archive.completed_at IS NOT NULL)
                    AND commit.id NOT IN (SELECT id FROM story_publish_commits
                        WHERE clustering_version_id = ? ORDER BY id DESC LIMIT 3)
                """, Long.class, version, ts(cutoff), version);
        samples.add(Map.of("phase", "cleanup-budget-" + cycle, "batches", batches,
                "polls", (batches + 7) / 8, "eligibleBacklog", backlog,
                "maximumBatchMilliseconds", maximumBatchMillis, "maximumArchiveMilliseconds", maximumArchiveMillis));
        assertThat(backlog).as("Cleanup must keep up within the default 40-batch allowance per input cycle").isZero();
    }

    private void assertSnapshotStatistics() {
        assertThat(count("""
                SELECT COUNT(*) FROM story_snapshots snapshot WHERE EXISTS (
                    (SELECT article.id FROM articles article WHERE article.first_seen_at <= snapshot.snapshot_watermark
                     EXCEPT SELECT member.article_id FROM story_snapshot_statistics_members member
                         WHERE member.snapshot_id = snapshot.id)
                    UNION ALL
                    (SELECT member.article_id FROM story_snapshot_statistics_members member
                         WHERE member.snapshot_id = snapshot.id
                     EXCEPT SELECT article.id FROM articles article WHERE article.first_seen_at <= snapshot.snapshot_watermark))
                """)).as("Exact historical article sets survive each compaction cycle").isZero();
    }

    private void sample(String phase, long milliseconds) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("phase", phase);
        sample.put("milliseconds", milliseconds);
        sample.put("relations", jdbc.queryForList("""
                SELECT relname, pg_table_size(relid) AS table_bytes, pg_indexes_size(relid) AS index_bytes,
                    n_live_tup, n_dead_tup FROM pg_stat_user_tables
                WHERE schemaname = current_schema() AND relname IN ('story_snapshot_members',
                    'story_pair_decisions', 'story_assignment_decisions', 'story_snapshot_retention') ORDER BY relname
                """));
        sample.put("members", count("SELECT COUNT(*) FROM story_snapshot_members"));
        sample.put("pairs", count("SELECT COUNT(*) FROM story_pair_decisions"));
        sample.put("archives", jdbc.queryForMap("""
                SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE completed_at IS NULL) AS incomplete,
                    COALESCE(SUM(pg_column_size(article_id_deltas)), 0) AS article_archive_bytes,
                    COALESCE(SUM(pg_column_size(kept_pair_ids)), 0) AS temporary_pair_archive_bytes
                FROM story_snapshot_retention
                """));
        samples.add(sample);
    }

    private org.springframework.context.ConfigurableApplicationContext application(String schema) {
        return new SpringApplicationBuilder(GlobalNewsEngineV1Application.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--spring.docker.compose.enabled=false",
                "--spring.datasource.url=" + dataSource.getUrl(),
                "--spring.datasource.username=" + dataSource.getUser(),
                "--spring.datasource.password=" + dataSource.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver", "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                "--stories.visible-version=" + VERSION, "--gdelt.ingestion.enabled=false",
                "--gdelt.staging.enabled=false", "--gdelt.retention.enabled=false", "--articles.enabled=false",
                "--stories.embeddings.incremental.enabled=false", "--stories.embeddings.repair.enabled=false",
                "--stories.snapshots.incremental.enabled=false", "--stories.snapshots.backfill.enabled=false",
                "--stories.snapshots.retention.enabled=false");
    }

    private void probe(HttpClient client, int port, AtomicBoolean stop, AtomicBoolean cleaning) {
        while (!stop.get()) {
            for (String endpoint : List.of("/articles?limit=1", "/stories?limit=1",
                    "/statistics?from=2026-11-01T12:00:00Z&to=2026-11-01T14:00:00Z")) {
                long started = System.nanoTime();
                boolean duringCleanup = cleaning.get();
                int status;
                String error = "";
                try {
                    status = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + endpoint))
                                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                } catch (Exception exception) {
                    status = -1;
                    error = exception.getClass().getSimpleName();
                }
                requests.add(Map.of("endpoint", endpoint, "status", status,
                        "milliseconds", elapsed(started), "duringCleanup", duringCleanup, "error", error));
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeReport() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("fixture", "107000 READY inputs; protected singleton baseline; 8 actual incremental cycles; first adds1000dense inputs, subsequent cycles replace1000dense+4old inputs and add8new; old timestamps spaced25h");
        report.put("scope", "GEM-125 storage acceptance; not GEM-124 dense comparison or five-minute capacity acceptance");
        report.put("cleanupBudget", "At most five simulated one-minute polls of8batches per five-minute input cycle; age eligibility accelerated to0 for the fixture; production2s statement/250ms lock/5s transaction timeouts");
        report.put("samples", samples);
        report.put("requests", requests);
        report.put("phases", meters.find("stories.snapshot.phase.latency").timers().stream()
                .map(timer -> Map.of("phase", timer.getId().getTag("phase"), "count", timer.count(),
                        "totalMilliseconds", timer.totalTime(TimeUnit.MILLISECONDS),
                        "maximumMilliseconds", timer.max(TimeUnit.MILLISECONDS))).toList());
        Path output = Path.of("target/gem125-retention-scale.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static PGSimpleDataSource source(String url) {
        var source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(System.getProperty("it.postgres.username", "gne"));
        source.setPassword(System.getProperty("it.postgres.password", "gne"));
        return source;
    }
}
