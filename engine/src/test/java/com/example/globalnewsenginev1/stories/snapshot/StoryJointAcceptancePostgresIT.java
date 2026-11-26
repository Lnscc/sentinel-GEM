package com.example.globalnewsenginev1.stories.snapshot;

import com.example.globalnewsenginev1.GlobalNewsEngineV1Application;
import com.example.globalnewsenginev1.articles.extraction.ArticleExtractorService;
import com.example.globalnewsenginev1.gdelt.staging.GdeltRawToStagingTransformer;
import com.example.globalnewsenginev1.gdelt.staging.parser.GdeltParserTests;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
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
import java.lang.management.ManagementFactory;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Controlled local workload, deliberately not a claim about production representativeness. */
public class StoryJointAcceptancePostgresIT {
    private static final String VERSION = "story-mvp-title-embedding-24h-v1.1.0";
    private static final int DIMENSION = 1536;
    private final int inputs = Integer.getInteger("it.joint.inputs", 107_000);
    private final int changes = Integer.getInteger("it.joint.changes", 1_000);
    private final int intervals = Integer.getInteger("it.joint.intervals", 3);
    private final int intervalSeconds = Integer.getInteger("it.joint.interval-seconds", 300);
    private final int groupSize = Integer.getInteger("it.joint.group-size", 4);
    private final String variant = System.getProperty("it.joint.variant", "integrated");
    private final Instant epoch = Instant.parse(System.getProperty("it.joint.epoch", "2026-11-01T12:00:00Z"));
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> imports = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> maintenance = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicBoolean currentlyMeasuring = new AtomicBoolean();
    private final AtomicLong heapPeak = new AtomicLong();
    private final AtomicLong stepHeapPeak = new AtomicLong();
    private long lastVectorCount;
    private PGSimpleDataSource source;
    private JdbcTemplate jdbc;
    private long version;
    private long protectedSnapshot;
    private AppenderBase<ILoggingEvent> retentionAppender;

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("it.joint.enabled")) throw new IllegalArgumentException("Explicit it.joint.enabled=true required");
        new StoryJointAcceptancePostgresIT().run();
    }

    @Test
    void publishesDenseIntervalsWithConcurrentReadsImportAndRetention() throws Exception {
        assumeTrue(Boolean.getBoolean("it.joint.enabled"), "Explicit local integrated workload opt-in required");
        run();
    }

    private void run() throws Exception {
        assertThat(variant).isIn("reference", "integrated");
        assertThat(inputs).isGreaterThanOrEqualTo(20);
        assertThat(changes).isBetween(1, inputs);
        assertThat(intervals).isGreaterThanOrEqualTo(3);
        assertThat(intervalSeconds).isPositive();
        assertThat(groupSize).isBetween(2, 20);
        String url = System.getProperty("it.postgres.jdbc-url", "jdbc:postgresql://localhost:55424/gem124");
        if (!url.matches("jdbc:postgresql://(?:localhost:55424|127\\.0\\.0\\.1:55424|postgres:5432|gem124-postgres:5432)/gem124")) {
            throw new IllegalArgumentException("Only the dedicated local GEM-124 Docker database is allowed");
        }
        String schema = "joint_" + UUID.randomUUID().toString().replace("-", "");
        var admin = dataSource(url);
        var adminJdbc = new JdbcTemplate(admin);
        adminJdbc.execute("CREATE SCHEMA " + schema);
        source = dataSource(url);
        source.setCurrentSchema(schema);
        jdbc = new JdbcTemplate(source);
        report.put("variant", variant);
        report.put("schema", schema);
        report.put("fixture", "Deterministic synthetic dense Float32 vectors; many small near-identical groups; synthetic local TSV replay");
        report.put("inputs", inputs);
        report.put("changesPerInterval", changes);
        report.put("intervalSeconds", intervalSeconds);
        report.put("epoch", epoch.toString());
        report.put("dimension", DIMENSION);
        report.put("groupSize", groupSize);
        report.put("sourceCommit", System.getProperty("it.joint.source", "unspecified"));
        report.put("referenceArithmetic", variant.equals("reference")
                ? "Caller must supply verified scalar ExactCosine/StoryIncrementalPairSearch classpath overlay"
                : "Current production classes");
        report.put("productionRepresentativenessProven", false);
        report.put("networkDownloadAndEmbeddingLatencyMeasured", false);
        report.put("importReplayPath", "Generated local EVENTS TSV -> GdeltRawToStagingTransformer -> ArticleExtractorService; no download or embedding stage");
        report.put("baselineConstruction", "Bulk persisted deterministic small-group assignments; timed runs use the real incremental publisher");
        report.put("semanticComparisonPendingExternalVerifier", true);
        report.put("javaRuntime", System.getProperty("java.runtime.version"));
        report.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        long setupStarted = System.nanoTime();
        try {
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).load().migrate();
            seed();
            report.put("fixturePreparationMs", elapsed(setupStarted));
            report.put("initialRelations", relations());
            report.put("distinctVectorHashes", count("SELECT COUNT(DISTINCT vector_hash) FROM story_embedding_artifacts"));
            assertThat(count("SELECT COUNT(DISTINCT vector_hash) FROM story_embedding_artifacts")).isEqualTo(inputs);
            try (var app = application(schema); var client = HttpClient.newHttpClient();
                 var workers = Executors.newFixedThreadPool(3)) {
                captureRetention();
                locationFixture(app.getBean(GdeltRawToStagingTransformer.class), app.getBean(ArticleExtractorService.class));
                int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
                var probe = workers.submit(() -> probe(client, port));
                var importer = workers.submit(() -> importReplay(app.getBean(GdeltRawToStagingTransformer.class),
                        app.getBean(ArticleExtractorService.class)));
                var sampler = workers.submit(() -> {
                    while (!stop.get()) {
                        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                        heapPeak.accumulateAndGet(used, Math::max);
                        if (currentlyMeasuring.get()) stepHeapPeak.accumulateAndGet(used, Math::max);
                        pause(20);
                    }
                });
                long scheduled = System.nanoTime();
                try {
                    for (int interval = 1; interval <= intervals; interval++) {
                        long release = scheduled + TimeUnit.SECONDS.toNanos((long) (interval - 1) * intervalSeconds);
                        while (System.nanoTime() < release) Thread.sleep(Math.min(200, Math.max(1,
                                TimeUnit.NANOSECONDS.toMillis(release - System.nanoTime()))));
                        Instant watermark = epoch.plusSeconds(7200L + (long) interval * intervalSeconds);
                        Map<String, Double> beforePhases = phases();
                        Map<String, Long> beforeGc = gcTotals();
                        Instant actualStart = Instant.now();
                        long started = System.nanoTime();
                        stepHeapPeak.set(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
                        StorySnapshotService.ProcessingResult result;
                        currentlyMeasuring.set(true);
                        try {
                            revise(interval, watermark);
                            result = service(watermark).processIncremental(1);
                        } finally {
                            currentlyMeasuring.set(false);
                        }
                        Instant actualEnd = Instant.now();
                        assertThat(result.succeededVersions()).isOne();
                        assertThat(result.failedVersions()).isZero();
                        long total = elapsed(started);
                        var step = new LinkedHashMap<String, Object>();
                        step.put("interval", interval);
                        step.put("watermark", watermark.toString());
                        step.put("actualStartUtc", actualStart.toString());
                        step.put("actualEndUtc", actualEnd.toString());
                        var gcDelta = new LinkedHashMap<String, Long>();
                        gcTotals().forEach((name, value) -> gcDelta.put(name, value - beforeGc.get(name)));
                        step.put("gcDelta", gcDelta);
                        step.put("elapsedIncludingInputReleaseMs", total);
                        var phaseDelta = new LinkedHashMap<String, Double>();
                        phases().forEach((name, value) -> phaseDelta.put(name, value - beforePhases.get(name)));
                        step.put("phasesMs", phaseDelta);
                        step.put("loadedDecodedVectors", lastVectorCount);
                        step.put("vectorPayloadBytes", lastVectorCount * DIMENSION * Float.BYTES);
                        step.put("heapUsedPeakSampledBytes", stepHeapPeak.get());
                        step.put("scheduledToPublicationMs", elapsed(release));
                        step.put("budgetPassed", elapsed(release) <= intervalSeconds * 1000L);
                        step.put("run", jdbc.queryForMap("""
                                SELECT read_article_count, changed_article_count, skipped_article_count,
                                    candidate_count, status FROM story_processing_runs
                                WHERE run_mode = 'INCREMENTAL' ORDER BY id DESC LIMIT 1
                                """));
                        step.put("snapshotInputHash", jdbc.queryForObject("""
                                SELECT snapshot.snapshot_input_hash FROM story_publish_commits commit
                                JOIN story_snapshots snapshot ON snapshot.id = commit.snapshot_id
                                ORDER BY commit.id DESC LIMIT 1
                                """, String.class));
                        step.put("semanticDigest", semanticDigest());
                        step.put("locations", count("SELECT COUNT(*) FROM story_locations"));
                        step.put("lineageEdges", count("SELECT COUNT(*) FROM story_lineage"));
                        step.put("relations", relations());
                        step.put("archives", archiveState());
                        steps.add(step);
                        assertThat(count("SELECT COUNT(*) FROM story_memberships WHERE current_marker = 1")).isEqualTo(inputs);
                        assertThat(count("SELECT COUNT(*) FROM story_snapshot_members WHERE snapshot_id = " + protectedSnapshot)).isEqualTo(inputs);
                        assertThat(count("SELECT COUNT(*) FROM gdelt_processing_errors")).isZero();
                    }
                    long retryStart = System.nanoTime();
                    var retry = service(epoch.plusSeconds(7200L + (long) intervals * intervalSeconds)).processIncremental(1);
                    assertThat(retry.reusedVersions()).isOne();
                    report.put("retryMs", elapsed(retryStart));
                    report.put("retryIdempotent", true);
                    long noopStart = System.nanoTime();
                    var noop = service(epoch.plusSeconds(7201L + (long) intervals * intervalSeconds)).processIncremental(1);
                    assertThat(noop.succeededVersions()).isOne();
                    report.put("noopMs", elapsed(noopStart));
                    report.put("noopCandidates", jdbc.queryForObject("SELECT candidate_count FROM story_processing_runs ORDER BY id DESC LIMIT 1", Long.class));
                    assertThat(report.get("noopCandidates")).isEqualTo(0L);
                } finally {
                    stop.set(true);
                    probe.get(15, TimeUnit.SECONDS);
                    importer.get(15, TimeUnit.SECONDS);
                    sampler.get(15, TimeUnit.SECONDS);
                }
                report.put("finalArchives", archiveState());
                report.put("finalRelations", relations());
                report.put("apiRequests", new ArrayList<>(requests));
                report.put("importBatches", new ArrayList<>(imports));
                report.put("stagedEvents", count("SELECT COUNT(*) FROM gdelt_events"));
                report.put("extractedImportedArticles", count("SELECT COUNT(*) FROM articles WHERE domain = 'joint-import.example'"));
                report.put("processingErrors", count("SELECT COUNT(*) FROM gdelt_processing_errors"));
                report.put("extractionErrors", count("SELECT COUNT(*) FROM article_extraction_errors"));
                report.put("heapUsedPeakSampledBytes", heapPeak.get());
                report.put("heapSamplingScope", "20ms samples after application/location setup through worker stop; each step peak covers input release and incremental publication; fixture construction excluded");
                report.put("cgroupCounterScope", "Container lifetime; memory.current is the final sample, not an interval peak");
                for (String file : List.of("memory.peak", "memory.current", "memory.events", "cpu.stat")) {
                    Path cgroup = Path.of("/sys/fs/cgroup", file);
                    if (Files.isReadable(cgroup)) report.put("cgroup." + file, Files.readString(cgroup).trim());
                }
                assertThat(requests).isNotEmpty().allSatisfy(row -> assertThat(row.get("status")).isEqualTo(200));
                assertThat(imports).isNotEmpty().allSatisfy(row -> assertThat(row.get("errors")).isEqualTo(0L));
            }
            report.put("workloadCompleted", true);
            report.put("scaleEligible", inputs >= 107_000 && changes >= 1_000 && intervalSeconds == 300);
            report.put("allIntervalBudgetsPassed", steps.stream().allMatch(row -> Boolean.TRUE.equals(row.get("budgetPassed"))));
            report.put("retentionProgressObserved", count("SELECT COUNT(*) FROM story_snapshot_retention WHERE completed_at IS NOT NULL") > 0);
            report.put("retentionEvents", new ArrayList<>(maintenance));
            report.put("retentionDuringMeasuredWorkObserved", maintenance.stream()
                    .anyMatch(event -> Boolean.TRUE.equals(event.get("duringMeasuredWork"))));
            report.put("pairRetentionCoverage", "One genuine stored-vector pair per warm/protected snapshot; obsolete warm pairs removable, protected baseline pair must survive");
            report.put("protectedSnapshotPairs", count("SELECT COUNT(*) FROM story_pair_decisions WHERE snapshot_id = " + protectedSnapshot));
            assertThat(report.get("protectedSnapshotPairs")).isEqualTo(1L);
            assertThat(count("""
                    SELECT COUNT(*) FROM story_snapshots snapshot WHERE EXISTS (
                         (SELECT article.id FROM articles article JOIN joint_fixture fixture ON fixture.ref = article.url_hash
                          WHERE article.first_seen_at <= snapshot.snapshot_watermark
                         EXCEPT SELECT member.article_id FROM story_snapshot_statistics_members member WHERE member.snapshot_id = snapshot.id)
                        UNION ALL
                        (SELECT member.article_id FROM story_snapshot_statistics_members member WHERE member.snapshot_id = snapshot.id
                         EXCEPT SELECT article.id FROM articles article JOIN joint_fixture fixture ON fixture.ref = article.url_hash
                          WHERE article.first_seen_at <= snapshot.snapshot_watermark))
                    """)).as("Exact historical article sets survive concurrent compaction").isZero();
            report.put("historicalStatisticsExact", true);
        } catch (Throwable failure) {
            report.put("workloadCompleted", false);
            report.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            if (retentionAppender != null) {
                ((Logger) org.slf4j.LoggerFactory.getLogger(StorySnapshotRetention.class)).detachAppender(retentionAppender);
                retentionAppender.stop();
            }
            report.put("retentionEvents", new ArrayList<>(maintenance));
            report.put("steps", steps);
            Path output = Path.of(System.getProperty("it.joint.output", "target/gem124-benchmark/" + variant + ".json"));
            Files.createDirectories(output.toAbsolutePath().getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            if (!Boolean.getBoolean("it.joint.keep-schema")) adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void seed() {
        jdbc.execute("ALTER TABLE story_clustering_versions DISABLE TRIGGER USER");
        jdbc.update("UPDATE story_clustering_versions SET status = CASE WHEN version_key = ? THEN 'ACTIVE' ELSE 'SHADOW' END", VERSION);
        jdbc.execute("ALTER TABLE story_clustering_versions ENABLE TRIGGER USER");
        version = jdbc.queryForObject("SELECT id FROM story_clustering_versions WHERE version_key = ?", Long.class, VERSION);
        jdbc.execute("CREATE TABLE joint_fixture (n integer PRIMARY KEY, ref varchar(64), group_ref varchar(64))");
        jdbc.update("""
                INSERT INTO joint_fixture SELECT n,
                    encode(sha256(convert_to('https://joint.example/' || n, 'UTF8')), 'hex'),
                    encode(sha256(convert_to('https://joint.example/' || (1 + ((n - 1) / ?) * ?), 'UTF8')), 'hex')
                    FROM generate_series(1, ?) n
                """, groupSize, groupSize, inputs);
        jdbc.update("""
                INSERT INTO articles (canonical_url, url_hash, domain, first_seen_at, created_at, updated_at)
                SELECT 'https://joint.example/' || n, ref, 'joint.example',
                    ?::timestamptz, ?, ?
                FROM joint_fixture
                """, ts(epoch), ts(epoch), ts(epoch));
        artifacts(inputs, 0, epoch);
        insertInputs(inputs, 0, epoch);
        var repository = new StorySnapshotRepository(jdbc);
        var rules = repository.findActiveVersion().getFirst();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        // Prewarm full historical snapshots so real 30-minute retention processes full-scale data
        // concurrently with the measured intervals, rather than only a tiny historical cohort.
        for (int index = 0; index < 5; index++) {
            Instant at = epoch.plusSeconds(60L * (index + 1));
            snapshot(repository, rules, at, StorySnapshotService.RunMode.INCREMENTAL, false, tx);
        }
        snapshot(repository, rules, epoch.plusSeconds(6000), StorySnapshotService.RunMode.BACKFILL, true, tx);
        jdbc.execute("ANALYZE");
    }

    private void snapshot(StorySnapshotRepository repository, StorySnapshotRepository.ClusteringVersion rules,
                          Instant at, StorySnapshotService.RunMode mode, boolean baseline, TransactionTemplate tx) {
        var metadata = repository.findSnapshotInputs(version, at);
        tx.executeWithoutResult(status -> {
            String hash = StorySnapshotCanonicalizer.snapshotInputHash(VERSION, at, metadata);
            var snapshot = repository.ensureSnapshot(rules, at, StorySnapshotCanonicalizer.snapshotKey(VERSION, at, hash), hash, metadata, at);
            var run = repository.claimRun(rules, snapshot, mode,
                    StorySnapshotCanonicalizer.runKey(VERSION, hash, mode, rules.pairRuleVersion()), at, Duration.ofMinutes(30)).orElseThrow();
            // Materialize an actual exact pair from two same-group vectors, without a quadratic seed backfill.
            List<String> refs = jdbc.queryForList("SELECT ref FROM joint_fixture WHERE n IN (1, 2) ORDER BY ref", String.class);
            var left = metadata.stream().filter(input -> input.articleRef().equals(refs.getFirst())).findFirst().orElseThrow();
            var right = metadata.stream().filter(input -> input.articleRef().equals(refs.getLast())).findFirst().orElseThrow();
            byte[] leftBytes = jdbc.queryForObject("SELECT vector_bytes FROM story_embedding_artifacts WHERE id = ?", byte[].class, left.embeddingArtifactId());
            byte[] rightBytes = jdbc.queryForObject("SELECT vector_bytes FROM story_embedding_artifacts WHERE id = ?", byte[].class, right.embeddingArtifactId());
            var score = ExactCosine.similarity(ExactCosine.decode(leftBytes, DIMENSION, left.vectorHash()),
                    ExactCosine.decode(rightBytes, DIMENSION, right.vectorHash()));
            assertThat(score).isGreaterThanOrEqualTo(rules.threshold());
            var pair = new StorySnapshotService.PairDecision(left, right, score,
                    Duration.between(left.effectiveAt(), right.effectiveAt()).abs().toSeconds(), 1,
                    "SAME_STORY", rules.pairRuleVersion(), false);
            repository.insertDecision(rules, snapshot, run, pair,
                    StorySnapshotCanonicalizer.decisionHash(snapshot, left, right, pair, rules.pairRuleVersion()), at);
            if (baseline) {
                protectedSnapshot = snapshot.id();
                jdbc.update("""
                        INSERT INTO stories (id, public_id, clustering_version_id, identity_anchor_article_ref,
                            representative_article_ref, state, effective_from, effective_to, created_by_run_id,
                            last_changed_by_run_id, created_at, updated_at, representative_article_id,
                            representative_canonical_url, representative_domain, representative_first_seen_at,
                            representative_title, member_count, source_count, independent_report_count)
                        SELECT md5(f.group_ref)::uuid, md5(f.group_ref)::uuid, ?, f.group_ref, f.group_ref,
                            'ACTIVE', MIN(input.effective_at), MAX(input.effective_at), ?, ?, ?, ?, representative.id,
                            representative.canonical_url, 'joint.example', representative.first_seen_at,
                            representative_input.normalized_title, COUNT(*), 1, 1
                        FROM joint_fixture f JOIN story_article_inputs input ON input.article_ref = f.ref
                        JOIN articles representative ON representative.url_hash = f.group_ref
                        JOIN story_article_inputs representative_input ON representative_input.article_id = representative.id
                        GROUP BY f.group_ref, representative.id, representative.canonical_url, representative.first_seen_at,
                            representative_input.normalized_title
                        """, version, run.id(), run.id(), ts(at), ts(at));
                jdbc.update("""
                        INSERT INTO story_assignment_decisions (decision_hash, clustering_version_id, run_id,
                            snapshot_id, article_input_id, article_ref, article_input_fingerprint, resulting_story_id,
                            result, assignment_reason, component_rule_version, best_comparison_article_ref,
                            best_cosine_similarity, created_at)
                        SELECT repeat(md5('initial:' || f.ref), 2), ?, ?, ?, input.id, f.ref,
                            input.article_input_fingerprint, md5(f.group_ref)::uuid, 'ASSIGNED', 'MEDOID_RADIUS',
                            ?, f.group_ref, 1, ? FROM joint_fixture f JOIN story_article_inputs input ON input.article_ref = f.ref
                        """, version, run.id(), snapshot.id(), repository.loadPartitionRules(snapshot.id()).componentRuleVersion(), ts(at));
                jdbc.update("""
                        INSERT INTO story_memberships (story_id, clustering_version_id, article_id, article_ref,
                            article_input_fingerprint, valid_from_run_id, decision_id, assignment_reason, current_marker, created_at)
                        SELECT decision.resulting_story_id, ?, input.article_id, input.article_ref,
                            input.article_input_fingerprint, ?, decision.id, 'MEDOID_RADIUS', 1, ?
                        FROM story_article_inputs input JOIN story_assignment_decisions decision ON decision.article_input_id = input.id
                        """, version, run.id(), ts(at));
                jdbc.update("DELETE FROM story_projection_dirty_sources");
            }
            jdbc.update("""
                    INSERT INTO story_publish_commits (publish_key, clustering_version_id, snapshot_id, run_id, fencing_token, published_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, StorySnapshotCanonicalizer.sha256(("publish:" + at).getBytes(StandardCharsets.UTF_8)),
                    version, snapshot.id(), run.id(), run.fencingToken(), ts(at));
            repository.completeRun(run, metadata.size(), baseline ? metadata.size() : 0, 0, 1, at);
        });
    }

    private void artifacts(int number, int revision, Instant now) {
        String sql = """
                INSERT INTO story_embedding_artifacts (embedding_model_id, embedding_model_version, embedding_dimension,
                    title_normalization_version, title_input_hash, status, vector_bytes, vector_hash, vector_norm,
                    attempt_count, ready_at, created_at, updated_at)
                SELECT embedding_model_id, embedding_model_version, embedding_dimension, title_normalization_version,
                    ?, 'READY', ?, ?, ?, 1, ?, ?, ? FROM story_clustering_versions WHERE id = ?
                """;
        for (int first = 1; first <= number; first += 128) {
            List<Object[]> batch = new ArrayList<>();
            for (int n = first; n < Math.min(number + 1, first + 128); n++) {
                var random = new Random(124_2026L + (n - 1) / groupSize);
                var noise = new Random(20_260_000L + (long) n * 31 + revision);
                ByteBuffer bytes = ByteBuffer.allocate(DIMENSION * Float.BYTES);
                double norm = 0;
                for (int dimension = 0; dimension < DIMENSION; dimension++) {
                    float value = (float) ((random.nextDouble() * 2 - 1) * (1 + (n % groupSize) * .001)
                            + (revision == 0 ? 0 : (noise.nextDouble() * 2 - 1) * .015));
                    bytes.putFloat(value);
                    norm += (double) value * value;
                }
                byte[] vector = bytes.array();
                batch.add(new Object[] { titleHash(n, revision), vector, StorySnapshotCanonicalizer.sha256(vector),
                        Math.sqrt(norm), ts(now), ts(now), ts(now), version });
            }
            jdbc.batchUpdate(sql, batch);
        }
    }

    private void insertInputs(int number, int revision, Instant now) {
        jdbc.update("""
                INSERT INTO story_article_inputs (clustering_version_id, article_id, article_ref, effective_at,
                    effective_at_source, normalized_title, title_input_hash, title_usability, article_input_fingerprint,
                    embedding_status, embedding_artifact_id, attempt_count, current_marker, created_at)
                SELECT ?, article.id, f.ref, article.first_seen_at, 'PUBLISHED_AT',
                    'Joint synthetic report ' || f.n || ' revision ' || ?, artifact.title_input_hash, 'USABLE',
                    repeat(md5(f.ref || ':' || ?), 2), 'READY', artifact.id, 1, 1, ?
                FROM joint_fixture f JOIN articles article ON article.url_hash = f.ref
                JOIN story_embedding_artifacts artifact ON artifact.title_input_hash = repeat(md5('title:' || f.n || ':' || ?), 2)
                WHERE f.n <= ?
                """, version, revision, revision, ts(now), revision, number);
    }

    private void revise(int revision, Instant now) {
        artifacts(changes, revision, now);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE story_article_inputs SET current_marker = NULL, superseded_at = ?
                    WHERE current_marker = 1 AND article_ref IN (SELECT ref FROM joint_fixture WHERE n <= ?)
                    """, ts(now), changes);
            insertInputs(changes, revision, now);
        });
    }

    private StorySnapshotService service(Instant now) {
        StorySnapshotRepository repository = new StorySnapshotRepository(jdbc) {
            @Override List<SnapshotInput> loadIncrementalSnapshotInputs(long snapshot, Set<String> changed,
                    List<ExistingAssignment> assignments, int hours) {
                List<SnapshotInput> loaded = variant.equals("reference") ? loadIncrementalSnapshotInputs(snapshot)
                        : super.loadIncrementalSnapshotInputs(snapshot, changed, assignments, hours);
                lastVectorCount = loaded.stream().filter(input -> input.decodedVector() != null).count();
                return loaded;
            }
        };
        var transactions = new DataSourceTransactionManager(source);
        return new StorySnapshotService(repository, transactions, Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(30), new StorySnapshotMetrics(meters),
                new StoryPublisher(jdbc, repository, transactions, meters, new StoryLocationMaterializer(jdbc)));
    }

    private org.springframework.context.ConfigurableApplicationContext application(String schema) {
        String minimumAge = System.getProperty("it.joint.retention.minimum-age", "PT30M");
        report.put("retentionMinimumAge", minimumAge);
        report.put("retentionPollSeconds", Integer.getInteger("it.joint.retention.poll-seconds", 60));
        return new SpringApplicationBuilder(GlobalNewsEngineV1Application.class).run(
                "--server.address=0.0.0.0", "--server.port=0", "--spring.docker.compose.enabled=false",
                "--spring.datasource.url=" + source.getUrl(), "--spring.datasource.username=" + source.getUser(),
                "--spring.datasource.password=" + source.getPassword(), "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                "--stories.visible-version=" + VERSION, "--gdelt.ingestion.enabled=false", "--gdelt.staging.enabled=false",
                "--gdelt.retention.enabled=false", "--articles.enabled=false", "--stories.embeddings.incremental.enabled=false",
                "--stories.embeddings.repair.enabled=false", "--stories.snapshots.incremental.enabled=false",
                "--stories.snapshots.backfill.enabled=false", "--stories.hnsw-shadow.enabled=false",
                "--stories.snapshots.retention.enabled=true", "--stories.snapshots.retention.initial-delay=PT1S",
                "--stories.snapshots.retention.minimum-age=" + minimumAge,
                "--stories.snapshots.retention.poll-interval=PT" + Integer.getInteger("it.joint.retention.poll-seconds", 60) + "S");
    }

    private void probe(HttpClient client, int port) {
        while (!stop.get()) {
            for (String endpoint : List.of("/articles?limit=20", "/stories?limit=20", "/statistics")) {
                long started = System.nanoTime();
                int status;
                try {
                    status = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + endpoint))
                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                } catch (Exception failure) { status = -1; }
                requests.add(Map.of("endpoint", endpoint, "status", status, "milliseconds", elapsed(started)));
            }
            pause(1000);
        }
    }

    private void importReplay(GdeltRawToStagingTransformer staging, ArticleExtractorService extractor) {
        int batch = 0;
        while (!stop.get()) {
            try {
                int index = ++batch;
                Instant at = epoch.plusSeconds(10_000L + index);
                String file = "joint-import-" + index + ".export.CSV.zip";
                long id = jdbc.queryForObject("""
                        INSERT INTO gdelt_import_files (dataset_type, source_file, source_url, source_timestamp,
                            status, row_count, started_at, completed_at)
                        VALUES ('EVENTS', ?, 'http://local.invalid/fixture', ?, 'COMPLETED', 10, ?, ?) RETURNING id
                        """, Long.class, file, ts(at), ts(at), ts(at));
                List<Object[]> rows = new ArrayList<>();
                for (int row = 1; row <= 10; row++) {
                    String[] columns = GdeltParserTests.eventRow().split("\\t", -1);
                    columns[0] = Long.toString(900_000_000L + index * 10L + row);
                    columns[60] = "https://joint-import.example/" + index + "/" + row;
                    rows.add(new Object[] { id, file, ts(at), row, String.join("\t", columns), ts(Instant.now()) });
                }
                jdbc.batchUpdate("""
                        INSERT INTO gdelt_event_payloads (import_file_id, source_file, source_timestamp,
                            row_number, raw_tsv, ingested_at) VALUES (?, ?, ?, ?, ?, ?)
                        """, rows);
                var staged = staging.transformCompletedRawRows(100);
                var extracted = extractor.extractArticles(100);
                imports.add(Map.of("batch", index, "staged", staged.totalStaged(), "articlesCreated", extracted.articlesCreated(),
                        "signalsCreated", extracted.signalsCreated(), "errors", staged.errors() + extracted.errorsCreated()));
            } catch (Exception failure) {
                imports.add(Map.of("errors", 1L, "error", failure.getClass().getSimpleName() + ": " + failure.getMessage()));
                return;
            }
            pause(1000);
        }
    }

    private void locationFixture(GdeltRawToStagingTransformer staging, ArticleExtractorService extractor) {
        int count = Math.min(8, inputs);
        String file = "joint-location-fixture.export.CSV.zip";
        long importId = jdbc.queryForObject("""
                INSERT INTO gdelt_import_files (dataset_type, source_file, source_url, source_timestamp,
                    status, row_count, started_at, completed_at)
                VALUES ('EVENTS', ?, 'http://local.invalid/location-fixture', ?, 'COMPLETED', ?, ?, ?) RETURNING id
                """, Long.class, file, ts(epoch), count, ts(Instant.now()), ts(Instant.now()));
        List<Object[]> rows = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            String[] columns = GdeltParserTests.eventRow().split("\\t", -1);
            columns[0] = Long.toString(800_000_000L + n);
            columns[51] = "4";
            columns[52] = "Berlin, Berlin, Germany";
            columns[53] = "GM";
            columns[54] = "GM16";
            columns[55] = "";
            columns[56] = "52.52";
            columns[57] = "13.405";
            columns[58] = "2950159";
            columns[60] = "https://joint.example/" + n;
            rows.add(new Object[] { importId, file, ts(epoch), n, String.join("\t", columns), ts(Instant.now()) });
        }
        jdbc.batchUpdate("""
                INSERT INTO gdelt_event_payloads (import_file_id, source_file, source_timestamp,
                    row_number, raw_tsv, ingested_at) VALUES (?, ?, ?, ?, ?, ?)
                """, rows);
        String mentionFile = "joint-location-fixture.mentions.CSV.zip";
        long mentionImport = jdbc.queryForObject("""
                INSERT INTO gdelt_import_files (dataset_type, source_file, source_url, source_timestamp,
                    status, row_count, started_at, completed_at)
                VALUES ('MENTIONS', ?, 'http://local.invalid/location-mentions', ?, 'COMPLETED', ?, ?, ?) RETURNING id
                """, Long.class, mentionFile, ts(epoch), count, ts(Instant.now()), ts(Instant.now()));
        List<Object[]> mentionRows = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            String[] columns = GdeltParserTests.mentionRow().split("\\t", -1);
            columns[0] = Long.toString(800_000_000L + n);
            columns[4] = "joint.example";
            columns[5] = "https://joint.example/" + n;
            columns[11] = "80";
            mentionRows.add(new Object[] { mentionImport, mentionFile, ts(epoch), n,
                    String.join("\t", columns), ts(Instant.now()) });
        }
        jdbc.batchUpdate("""
                INSERT INTO gdelt_mention_payloads (import_file_id, source_file, source_timestamp,
                    row_number, raw_tsv, ingested_at) VALUES (?, ?, ?, ?, ?, ?)
                """, mentionRows);
        var staged = staging.transformCompletedRawRows(100);
        var extracted = extractor.extractArticles(100);
        assertThat(staged.errors()).isZero();
        assertThat(staged.eventsStaged()).isEqualTo(count);
        assertThat(extracted.errorsCreated()).isZero();
        assertThat(extracted.articlesCreated()).as("Location events link existing fixture articles").isZero();
        assertThat(extracted.signalsCreated()).isEqualTo(2 * count);
        new StoryLocationMaterializer(jdbc).refresh(version);
        long locations = count("SELECT COUNT(*) FROM story_locations");
        assertThat(locations).as("Real staged/extracted event coordinates materialize nonempty story locations").isPositive();
        report.put("locationCoverage", true);
        report.put("initialLocationCount", locations);
        report.put("locationFixture", "Synthetic Berlin EVENTS TSV replay linked to existing fixture article URLs; real extractor/materializer before timing");
    }

    private void captureRetention() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(StorySnapshotRetention.class);
        retentionAppender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                if (!event.getMessage().startsWith("Story snapshot retention: snapshot=")) return;
                Object[] arguments = event.getArgumentArray();
                if (arguments == null || arguments.length < 6) return;
                long duration = ((Number) arguments[5]).longValue();
                var record = new LinkedHashMap<String, Object>();
                record.put("completedUtc", Instant.ofEpochMilli(event.getTimeStamp()).toString());
                record.put("approximateStartedUtc", Instant.ofEpochMilli(event.getTimeStamp() - duration).toString());
                record.put("snapshot", arguments[0]);
                record.put("removedPairs", arguments[1]);
                record.put("removedMembers", arguments[2]);
                record.put("complete", arguments[3]);
                record.put("archiveMilliseconds", arguments[4]);
                record.put("transactionMilliseconds", duration);
                record.put("duringMeasuredWork", currentlyMeasuring.get());
                maintenance.add(record);
            }
        };
        retentionAppender.setContext(logger.getLoggerContext());
        retentionAppender.start();
        logger.addAppender(retentionAppender);
    }

    private Map<String, Long> gcTotals() {
        long collections = 0;
        long millis = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, collector.getCollectionCount());
            millis += Math.max(0, collector.getCollectionTime());
        }
        return Map.of("collectionCount", collections, "collectionMilliseconds", millis);
    }

    private String semanticDigest() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String query : List.of("""
                SELECT member.article_ref || ':' || member.article_input_fingerprint || ':' || member.story_id || ':' ||
                    decision.assignment_reason || ':' || COALESCE(decision.best_comparison_article_ref, '') || ':' ||
                    COALESCE(decision.best_cosine_similarity::text, '') AS canonical
                FROM story_memberships member JOIN story_assignment_decisions decision ON decision.id = member.decision_id
                WHERE member.current_marker = 1 ORDER BY member.article_ref
                """, """
                SELECT id::text || ':' || public_id::text || ':' || representative_article_ref || ':' || state || ':' || member_count || ':' ||
                    effective_from::text || ':' || effective_to::text AS canonical FROM stories ORDER BY id
                """, """
                SELECT story_id::text || ':' || rank || ':' || name || ':' || COALESCE(country_code, '') || ':' ||
                    COALESCE(region_code, '') || ':' || latitude::text || ':' || longitude::text || ':' || source || ':' ||
                    confidence || ':' || supporting_articles || ':' || supporting_domains AS canonical
                FROM story_locations ORDER BY story_id, rank
                """, """
                SELECT predecessor_story_id::text || ':' || successor_story_id::text || ':' || reason AS canonical
                FROM story_lineage ORDER BY predecessor_story_id, successor_story_id, reason
                """)) {
            jdbc.query(query, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                byte[] value = row.getString("canonical").getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
                digest.update(value);
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private List<Map<String, Object>> relations() {
        return jdbc.queryForList("""
                SELECT relname, pg_table_size(relid) AS table_bytes, pg_indexes_size(relid) AS index_bytes,
                    n_live_tup, n_dead_tup FROM pg_stat_user_tables WHERE schemaname = current_schema()
                AND relname IN ('story_snapshot_members', 'story_embedding_artifacts', 'story_pair_decisions',
                    'story_assignment_decisions', 'story_snapshot_retention') ORDER BY relname
                """);
    }

    private Map<String, Object> archiveState() {
        return jdbc.queryForMap("""
                SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE completed_at IS NOT NULL) AS completed,
                    COUNT(*) FILTER (WHERE completed_at IS NULL) AS incomplete FROM story_snapshot_retention
                """);
    }

    private Map<String, Double> phases() {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String phase : List.of("load", "compare", "publish")) {
            var timer = meters.find("stories.snapshot.phase.latency").tag("phase", phase).timer();
            result.put(phase, timer == null ? 0d : timer.totalTime(TimeUnit.MILLISECONDS));
        }
        return result;
    }

    private static PGSimpleDataSource dataSource(String url) {
        var data = new PGSimpleDataSource();
        data.setURL(url);
        data.setUser(System.getProperty("it.postgres.username", "gem124"));
        data.setPassword(System.getProperty("it.postgres.password", "gem124"));
        return data;
    }

    private static String titleHash(int n, int revision) {
        try {
            String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(("title:" + n + ":" + revision).getBytes(StandardCharsets.UTF_8)));
            return md5 + md5;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); stop.set(true); }
    }
}
