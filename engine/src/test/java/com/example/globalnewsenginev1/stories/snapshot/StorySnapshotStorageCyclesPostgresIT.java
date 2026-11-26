package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Space reuse experiment, not elapsed-time, production-replay or VPS capacity acceptance. */
public class StorySnapshotStorageCyclesPostgresIT {
    private static final String VERSION = "story-mvp-title-embedding-24h-v1.1.0";
    private static final int WARMUP = 2;
    private final int inputs = Integer.getInteger("it.storage.inputs", 107_000);
    private final int history = Integer.getInteger("it.storage.history-inputs", 107_000);
    private final int cycles = Integer.getInteger("it.storage.cycles", 8);
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private JdbcTemplate jdbc;
    private StorySnapshotRepository repository;
    private StorySnapshotRepository.ClusteringVersion rules;
    private List<StorySnapshotRepository.SnapshotInput> metadata;
    private TransactionTemplate transaction;
    private Instant epoch;
    private long protectedSnapshot;
    private int snapshotNumber;

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("it.storage.enabled")) throw new IllegalArgumentException("Explicit it.storage.enabled=true required");
        new StorySnapshotStorageCyclesPostgresIT().run();
    }

    @Test
    void reusesVacuumedSnapshotHeapAndIndexesAtConstantPopulation() throws Exception {
        assumeTrue(Boolean.getBoolean("it.storage.enabled"), "Storage experiment requires explicit opt-in");
        run();
    }

    private void run() throws Exception {
        assertThat(inputs).isGreaterThanOrEqualTo(100);
        assertThat(history).isGreaterThanOrEqualTo(0);
        assertThat(cycles).isGreaterThanOrEqualTo(8);
        String url = System.getProperty("it.storage.jdbc-url", "jdbc:postgresql://localhost:55430/gem124_storage");
        if (!url.matches("jdbc:postgresql://(?:localhost:55430|127\\.0\\.0\\.1:55430|storage-postgres:5432)/gem124_storage")) {
            throw new IllegalArgumentException("Only the dedicated GEM-124 storage database is allowed");
        }
        Path output = Path.of(System.getProperty("it.storage.output", "target/gem124-storage/cycles.json"));
        if (Files.exists(output)) throw new IllegalArgumentException("Preserve existing evidence; choose a fresh it.storage.output");
        String schema = "storage_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(dataSource(url, null));
        admin.execute("CREATE SCHEMA " + schema);
        var source = dataSource(url, schema);
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        epoch = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minus(Duration.ofDays(2));
        report.put("scope", "Isolated constant-population PostgreSQL snapshot storage cycles; no VPS capacity claim");
        report.put("vpsAcceptancePassed", false);
        report.put("inputs", inputs);
        report.put("fixedNoncurrentHistoryInputs", history);
        report.put("cycles", cycles);
        report.put("warmupCycles", WARMUP);
        report.put("scaleEligible", inputs >= 107_000 && cycles - WARMUP >= 6);
        report.put("retentionMinimumAge", "PT30M");
        report.put("timeModel", "Old fixture timestamps, real wall-clock 30-minute cutoff; drain normal bounded retention batches directly, no scheduler-speed claim");
        report.put("vacuum", "Ordinary VACUUM (ANALYZE, TRUNCATE FALSE); no FULL, REINDEX, CLUSTER or table rewrite");
        report.put("fixture", "A distinct embedding artifact/title key per current and historical input, with one compressible valid Float32 payload; no vector-arithmetic workload; noncurrent history excluded from snapshots");
        report.put("growthGate", "Across six sustained cycles, each member heap/index may allocate at most its independently measured one-generation footprint beyond warmup high-water; require observed heap/index free-space consumption");
        report.put("archivePolicy", "Exact historical ID archives and run metadata legitimately accumulate; report separately, do not claim an absolute whole-database plateau");
        try {
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).load().migrate();
            admin.execute("CREATE EXTENSION IF NOT EXISTS pg_freespacemap WITH SCHEMA public");
            seed();
            long distinctArtifacts = scalar("SELECT COUNT(DISTINCT embedding_artifact_id) FROM story_article_inputs");
            assertThat(distinctArtifacts).isEqualTo((long) inputs + history);
            report.put("distinctEmbeddingArtifactKeys", distinctArtifacts);
            report.put("currentDistinctEmbeddingArtifactKeys", scalar("SELECT COUNT(DISTINCT embedding_artifact_id) FROM story_article_inputs WHERE current_marker = 1"));
            repository = new StorySnapshotRepository(jdbc);
            rules = repository.findActiveVersion().getFirst();
            metadata = repository.findSnapshotInputs(rules.id(), epoch.plusSeconds(1));
            assertThat(metadata).hasSize(inputs);
            protectedSnapshot = snapshot(StorySnapshotService.RunMode.BACKFILL, true);
            ordinaryVacuum();
            var generation = storage();
            report.put("oneGeneration", generation);
            for (int index = 0; index < 3; index++) snapshot(StorySnapshotService.RunMode.INCREMENTAL, false);
            ordinaryVacuum();
            report.put("initialStorage", storage());
            var retention = new StorySnapshotRetention(jdbc, new DataSourceTransactionManager(source),
                    Duration.ofMinutes(30), 10_000, 8, Duration.ofSeconds(2),
                    Duration.ofMillis(250), Duration.ofSeconds(5));
            for (int cycle = 1; cycle <= cycles; cycle++) {
                long started = System.nanoTime();
                var step = new LinkedHashMap<String, Object>();
                samples.add(step);
                step.put("cycle", cycle);
                step.put("warmup", cycle <= WARMUP);
                step.put("beforeWrite", storage());
                long added = snapshot(StorySnapshotService.RunMode.INCREMENTAL, false);
                step.put("newSnapshot", added);
                step.put("afterWrite", storage());
                step.put("rowsWritten", inputs);
                int polls = 0;
                while (scalar("SELECT COUNT(*) FROM story_snapshot_retention WHERE completed_at IS NOT NULL") < cycle) {
                    if (++polls > (inputs / 10_000 + 2) * 4) throw new AssertionError("Retention made insufficient bounded-batch progress");
                    retention.compactSnapshots();
                }
                step.put("retentionPolls", polls);
                ordinaryVacuum();
                step.put("afterVacuum", storage());
                step.put("compactedSnapshots", scalar("SELECT COUNT(*) FROM story_snapshot_retention WHERE completed_at IS NOT NULL"));
                step.put("memberRows", scalar("SELECT COUNT(*) FROM story_snapshot_members"));
                assertThat(step.get("memberRows")).isEqualTo(4L * inputs);
                verifyHistoryAndEvidence();
                step.put("historicalStatisticsExact", true);
                step.put("protectedEvidenceIntact", true);
                step.put("elapsedMilliseconds", (System.nanoTime() - started) / 1_000_000);
            }
            var findings = assessGrowth(generation);
            report.put("growthAssessment", findings);
            report.put("constantLogicalPopulationVerified", true);
            report.put("completed", true);
            report.put("storageReusePassed", findings.stream().allMatch(row -> Boolean.TRUE.equals(row.get("boundedGrowth")))
                    && observedReuse(false) == cycles - WARMUP && observedReuse(true) > 0);
            report.put("sustainedHeapReuseCycles", observedReuse(false));
            report.put("sustainedIndexReuseCycles", observedReuse(true));
            assertThat(report.get("storageReusePassed")).as("Physical allocation/reuse gate; retain raw failing samples").isEqualTo(true);
        } catch (Throwable failure) {
            report.put("storageReusePassed", false);
            report.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            report.put("samples", samples);
            try {
                Files.createDirectories(output.toAbsolutePath().getParent());
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            } finally {
                if (!Boolean.getBoolean("it.storage.keep-schema")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private void seed() {
        jdbc.execute("ALTER TABLE story_clustering_versions DISABLE TRIGGER USER");
        jdbc.update("UPDATE story_clustering_versions SET status = CASE WHEN version_key = ? THEN 'ACTIVE' ELSE 'SHADOW' END", VERSION);
        jdbc.execute("ALTER TABLE story_clustering_versions ENABLE TRIGGER USER");
        long version = scalar("SELECT id FROM story_clustering_versions WHERE version_key = '" + VERSION + "'");
        byte[] vector = ByteBuffer.allocate(1536 * Float.BYTES).putFloat(1).array();
        String vectorHash = StorySnapshotCanonicalizer.sha256(vector);
        jdbc.update("""
                INSERT INTO story_embedding_artifacts (embedding_model_id, embedding_model_version, embedding_dimension,
                    title_normalization_version, title_input_hash, status, vector_bytes, vector_hash, vector_norm,
                    attempt_count, ready_at, created_at, updated_at)
                SELECT embedding_model_id, embedding_model_version, embedding_dimension, title_normalization_version,
                    repeat(md5('storage-title:' || n), 2), 'READY', ?, ?, 1, 1, ?, ?, ?
                FROM story_clustering_versions CROSS JOIN generate_series(1, ?) n WHERE id = ?
                """, vector, vectorHash, ts(epoch), ts(epoch), ts(epoch), inputs + history, version);
        jdbc.update("""
                INSERT INTO articles (canonical_url, url_hash, domain, first_seen_at, created_at, updated_at)
                SELECT 'https://storage-fixture.example/' || n, repeat(md5('storage:' || n), 2),
                    'storage-fixture.example', ?, ?, ? FROM generate_series(1, ?) n
                """, ts(epoch), ts(epoch), ts(epoch), inputs + history);
        jdbc.update("""
                INSERT INTO story_article_inputs (clustering_version_id, article_id, article_ref, effective_at,
                    effective_at_source, normalized_title, title_input_hash, title_usability, article_input_fingerprint,
                    embedding_status, embedding_artifact_id, attempt_count, current_marker, created_at, superseded_at)
                SELECT ?, article.id, article.url_hash, ?, 'PUBLISHED_AT', 'Storage fixture title ' || article.id, artifact.title_input_hash, 'USABLE',
                    repeat(md5(article.url_hash), 2), 'READY', artifact.id, 1,
                    CASE WHEN article.id <= ? THEN 1 ELSE NULL END, ?, CASE WHEN article.id > ? THEN ?::timestamptz ELSE NULL END
                FROM articles article JOIN story_embedding_artifacts artifact
                    ON artifact.title_input_hash = repeat(md5('storage-title:' || article.id), 2)
                """, version, ts(epoch), inputs, ts(epoch), inputs, ts(epoch.plusSeconds(1)));
        jdbc.execute("ALTER TABLE story_snapshot_members SET (autovacuum_enabled = false)");
        report.put("autovacuumModel", "Disabled only on fixture snapshot-member heap for deterministic explicit ordinary-vacuum cycles");
    }

    private long snapshot(StorySnapshotService.RunMode mode, boolean protectAssignments) {
        Instant at = epoch.plusSeconds(60L * ++snapshotNumber);
        return transaction.execute(status -> {
            String hash = StorySnapshotCanonicalizer.snapshotInputHash(VERSION, at, metadata);
            var snapshot = repository.ensureSnapshot(rules, at,
                    StorySnapshotCanonicalizer.snapshotKey(VERSION, at, hash), hash, metadata, at);
            var claim = repository.claimRun(rules, snapshot, mode,
                    StorySnapshotCanonicalizer.runKey(VERSION, hash, mode, rules.pairRuleVersion()),
                    at, Duration.ofMinutes(30)).orElseThrow();
            var pairInputs = metadata.stream().sorted(java.util.Comparator.comparing(StorySnapshotRepository.SnapshotInput::articleRef)).limit(2).toList();
            var left = pairInputs.getFirst();
            var right = pairInputs.getLast();
            var pair = new StorySnapshotService.PairDecision(left, right, java.math.BigDecimal.ONE, 0, 1, "SAME_STORY", rules.pairRuleVersion(), false);
            repository.insertDecision(rules, snapshot, claim, pair,
                    StorySnapshotCanonicalizer.decisionHash(snapshot, left, right, pair, rules.pairRuleVersion()), at);
            if (protectAssignments) {
                jdbc.update("""
                        INSERT INTO story_assignment_decisions (decision_hash, clustering_version_id, run_id,
                            snapshot_id, article_input_id, article_ref, article_input_fingerprint, result,
                            assignment_reason, component_rule_version, created_at)
                        SELECT repeat(md5('assignment:' || article_ref), 2), ?, ?, ?, id, article_ref,
                            article_input_fingerprint, 'UNASSIGNED', 'STORAGE_FIXTURE', ?, ?
                        FROM story_article_inputs WHERE current_marker = 1
                        """, rules.id(), claim.id(), snapshot.id(), repository.loadPartitionRules(snapshot.id()).componentRuleVersion(), ts(at));
            }
            jdbc.update("""
                    INSERT INTO story_publish_commits (publish_key, clustering_version_id, snapshot_id, run_id, fencing_token, published_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, StorySnapshotCanonicalizer.sha256(("publish:" + at).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    rules.id(), snapshot.id(), claim.id(), claim.fencingToken(), ts(at));
            repository.completeRun(claim, inputs, 0, 0, 1, at);
            return snapshot.id();
        });
    }

    private void ordinaryVacuum() {
        // Retain free tail pages too, so subsequent allocation demonstrates reuse rather than shrink/regrow.
        jdbc.execute("VACUUM (ANALYZE, TRUNCATE FALSE) story_snapshot_members");
        jdbc.execute("VACUUM (ANALYZE, TRUNCATE FALSE) story_pair_decisions");
        jdbc.execute("VACUUM (ANALYZE, TRUNCATE FALSE) story_snapshot_retention");
    }

    private void verifyHistoryAndEvidence() {
        assertThat(scalar("SELECT COUNT(*) FROM story_snapshot_members WHERE snapshot_id = " + protectedSnapshot)).isEqualTo(inputs);
        assertThat(scalar("SELECT COUNT(*) FROM story_pair_decisions WHERE snapshot_id = " + protectedSnapshot)).isOne();
        assertThat(scalar("SELECT COUNT(*) FROM story_assignment_decisions WHERE snapshot_id = " + protectedSnapshot)).isEqualTo(inputs);
        assertThat(scalar("SELECT COUNT(*) FROM story_snapshot_retention WHERE snapshot_id = " + protectedSnapshot)).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM story_article_inputs WHERE current_marker = 1")).isEqualTo(inputs);
        assertThat(scalar("SELECT COUNT(*) FROM story_article_inputs WHERE current_marker IS NULL")).isEqualTo(history);
        assertThat(scalar("""
                SELECT COUNT(*) FROM story_snapshots snapshot WHERE EXISTS (
                    (SELECT article_id FROM story_article_inputs WHERE current_marker = 1
                     EXCEPT SELECT article_id FROM story_snapshot_statistics_members WHERE snapshot_id = snapshot.id)
                    UNION ALL
                    (SELECT article_id FROM story_snapshot_statistics_members WHERE snapshot_id = snapshot.id
                     EXCEPT SELECT article_id FROM story_article_inputs WHERE current_marker = 1))
                """)).as("Every historical snapshot retains its exact article set").isZero();
    }

    private Map<String, Object> storage() {
        var result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> relations = jdbc.queryForList("""
                SELECT relation.oid::bigint AS oid, relation.relname AS name, relation.relkind::text AS kind,
                    pg_relation_size(relation.oid) AS bytes
                FROM pg_class relation WHERE relation.oid = 'story_snapshot_members'::regclass
                    OR relation.oid IN (SELECT indexrelid FROM pg_index WHERE indrelid = 'story_snapshot_members'::regclass)
                ORDER BY relation.relname
                """);
        for (var relation : relations) {
            long oid = ((Number) relation.remove("oid")).longValue();
            relation.put("freeBytes", jdbc.queryForObject("SELECT COALESCE(SUM(avail), 0)::bigint FROM public.pg_freespace(?::oid::regclass)", Long.class, oid));
        }
        result.put("memberRelations", relations);
        result.put("archiveTableBytes", scalar("SELECT pg_table_size('story_snapshot_retention')"));
        result.put("archiveIndexBytes", scalar("SELECT pg_indexes_size('story_snapshot_retention')"));
        result.put("runMetadataBytes", scalar("SELECT pg_total_relation_size('story_snapshots') + pg_total_relation_size('story_processing_runs') + pg_total_relation_size('story_publish_commits')"));
        result.put("articleInputBytes", scalar("SELECT pg_total_relation_size('story_article_inputs')"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> memberRelations(Object state) {
        return (List<Map<String, Object>>) ((Map<String, Object>) state).get("memberRelations");
    }

    private List<Map<String, Object>> assessGrowth(Map<String, Object> generation) {
        var result = new ArrayList<Map<String, Object>>();
        for (var relation : memberRelations(generation)) {
            String name = relation.get("name").toString();
            long unit = ((Number) relation.get("bytes")).longValue();
            long warmHigh = samples.subList(0, WARMUP).stream().mapToLong(step -> relationValue(step.get("afterWrite"), name, "bytes")).max().orElseThrow();
            long sustainedHigh = samples.subList(WARMUP, cycles).stream().mapToLong(step -> relationValue(step.get("afterWrite"), name, "bytes")).max().orElseThrow();
            long growth = Math.max(0, sustainedHigh - warmHigh);
            result.add(Map.of("relation", name, "oneGenerationBytes", unit, "warmupHighWaterBytes", warmHigh,
                    "sustainedHighWaterBytes", sustainedHigh, "additionalBytes", growth,
                    "boundedGrowth", growth <= unit, "strictPlateau", growth == 0));
        }
        return result;
    }

    private int observedReuse(boolean indexes) {
        int reused = 0;
        for (var step : samples.subList(WARMUP, cycles)) {
            long before = memberRelations(step.get("beforeWrite")).stream()
                    .filter(row -> indexes == row.get("kind").equals("i")).mapToLong(row -> ((Number) row.get("freeBytes")).longValue()).sum();
            long after = memberRelations(step.get("afterWrite")).stream()
                    .filter(row -> indexes == row.get("kind").equals("i")).mapToLong(row -> ((Number) row.get("freeBytes")).longValue()).sum();
            if (before > after) reused++;
        }
        return reused;
    }

    private static long relationValue(Object state, String name, String key) {
        return memberRelations(state).stream().filter(row -> row.get("name").equals(name))
                .mapToLong(row -> ((Number) row.get(key)).longValue()).findFirst().orElseThrow();
    }

    private static PGSimpleDataSource dataSource(String url, String schema) {
        var source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser("gem124_storage");
        source.setPassword("gem124-storage-disposable-test");
        source.setConnectTimeout(10);
        source.setSocketTimeout(120);
        // The extension's SQL wrapper resolves its second overload through search_path.
        if (schema != null) source.setCurrentSchema(schema + ",public");
        return source;
    }

    private long scalar(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
