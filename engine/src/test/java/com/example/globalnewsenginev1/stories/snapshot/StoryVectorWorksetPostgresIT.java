package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Run each mode/history/no-op combination in a fresh JVM; this measures loading, not GEM-124 throughput. */
class StoryVectorWorksetPostgresIT {
    private static final String VERSION = "story-mvp-title-embedding-24h-v1.1.0";
    private static final Instant WATERMARK = Instant.parse("2026-11-01T12:00:00Z");
    private static final int CHANGES = 1_000;
    private static final int DIMENSION = 1536;

    @Test
    void measuresFrozenVectorLoadingWithIndependentOldHistory() throws Exception {
        assumeTrue(Boolean.getBoolean("it.vector.scale"), "Enable explicitly with -Dit.vector.scale=true");
        String mode = System.getProperty("it.vector.mode", "selective");
        assertThat(mode).isIn("reference", "selective");
        int history = Integer.getInteger("it.vector.history", 105_000);
        assertThat(history).isGreaterThanOrEqualTo(105_000);
        boolean noop = Boolean.getBoolean("it.vector.noop");
        String url = System.getProperty("it.postgres.jdbc-url", "jdbc:postgresql://localhost:55426/gne");
        assertThat(url).as("Use only a dedicated local acceptance database")
                .matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):55426/[a-zA-Z0-9_]+(?:\\?.*)?");
        String schema = "vector_scale_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(source(url));
        admin.execute("CREATE SCHEMA " + schema);
        var dataSource = source(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        var jdbc = new JdbcTemplate(dataSource);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .schemas(schema).defaultSchema(schema).load().migrate();
            var repository = new StorySnapshotRepository(jdbc);
            seed(jdbc, history);
            var version = repository.findActiveVersion().getFirst();
            var metadata = repository.findSnapshotInputs(version.id(), WATERMARK);
            assertThat(metadata).hasSize(history + 2 * CHANGES);
            String hash = StorySnapshotCanonicalizer.snapshotInputHash(VERSION, WATERMARK, metadata);
            var snapshot = transaction.execute(status -> {
                var frozen = repository.ensureSnapshot(version, WATERMARK,
                        StorySnapshotCanonicalizer.snapshotKey(VERSION, WATERMARK, hash), hash, metadata, WATERMARK);
                repository.claimRun(version, frozen, StorySnapshotService.RunMode.INCREMENTAL,
                        StorySnapshotCanonicalizer.runKey(VERSION, hash, StorySnapshotService.RunMode.INCREMENTAL,
                                version.pairRuleVersion()), WATERMARK, Duration.ofMinutes(30)).orElseThrow();
                return frozen;
            });
            Set<String> changed = new HashSet<>();
            List<StorySnapshotRepository.ExistingAssignment> assignments = new ArrayList<>();
            for (var input : metadata) {
                if (!noop && input.normalizedTitle().equals("Synthetic changed article")) {
                    changed.add(input.articleRef());
                } else {
                    assignments.add(new StorySnapshotRepository.ExistingAssignment(input.articleRef(),
                            UUID.nameUUIDFromBytes(input.articleRef().getBytes(StandardCharsets.UTF_8)), input.articleRef()));
                }
            }
            assertThat(changed).hasSize(noop ? 0 : CHANGES);
            // Do not retain the preparation list during the measured loader invocation.
            metadata.clear();
            System.gc();
            var pools = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == MemoryType.HEAP).toList();
            pools.forEach(pool -> pool.resetPeakUsage());
            long heapBefore = heapUsed();
            AtomicLong heapPeak = new AtomicLong(heapBefore);
            List<StorySnapshotRepository.SnapshotInput> loaded;
            long loadNanos;
            try (var sampler = Executors.newSingleThreadScheduledExecutor()) {
                sampler.scheduleAtFixedRate(() -> heapPeak.accumulateAndGet(heapUsed(), Math::max),
                        0, 5, TimeUnit.MILLISECONDS);
                long started = System.nanoTime();
                loaded = transaction.execute(status -> mode.equals("reference")
                        ? repository.loadIncrementalSnapshotInputs(snapshot.id())
                        : repository.loadIncrementalSnapshotInputs(snapshot.id(), changed, assignments, version.windowHours()));
                loadNanos = System.nanoTime() - started;
                heapPeak.accumulateAndGet(heapUsed(), Math::max);
            }
            long poolPeakSum = pools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
            assertThat(loaded).hasSize(history + 2 * CHANGES);
            long vectors = loaded.stream().filter(input -> input.decodedVector() != null).count();
            assertThat(vectors).isEqualTo(mode.equals("reference") ? loaded.size() : noop ? 0 : 2L * CHANGES);
            assertThat(loaded).allSatisfy(input -> assertThat(input.vectorBytes()).isNull());
            var rules = repository.loadPartitionRules(snapshot.id());
            var search = new StoryIncrementalPairSearch(version, loaded);
            long compareStarted = System.nanoTime();
            var partition = StoryPartitionService.incrementalPartition(rules, loaded, search, changed, assignments);
            var result = search.result();
            long compareNanos = System.nanoTime() - compareStarted;
            assertThat(partition.components()).hasSize(noop ? 0 : CHANGES);
            assertThat(result.comparedPairs()).isEqualTo(noop ? 0 : CHANGES);
            assertThat(result.decisions()).hasSize(noop ? 0 : CHANGES);
            assertThat(partition.components()).allSatisfy(component -> {
                assertThat(component.members()).hasSize(2);
                assertThat(component.members()).allSatisfy(member ->
                        assertThat(member.similarityToMedoid()).isEqualByComparingTo("1.000000"));
            });
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("mode", mode);
            report.put("noop", noop);
            report.put("logicalInputs", loaded.size());
            report.put("unrelatedHistory", history);
            report.put("changedInputs", changed.size());
            report.put("dimension", DIMENSION);
            report.put("loadedDecodedVectors", vectors);
            report.put("vectorPayloadBytes", vectors * DIMENSION * Float.BYTES);
            report.put("loadMs", loadNanos / 1_000_000.0);
            report.put("compareMs", compareNanos / 1_000_000.0);
            report.put("heapBeforeBytes", heapBefore);
            report.put("heapUsedPeakSampledBytes", heapPeak.get());
            report.put("heapPoolPeakSumBytes", poolPeakSum);
            report.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
            report.put("snapshotInputHash", hash);
            report.put("snapshotKey", snapshot.key());
            report.put("comparedPairs", result.comparedPairs());
            report.put("semanticDigest", semanticDigest(partition, result));
            report.put("postgresVersion", jdbc.queryForObject("SHOW server_version", String.class));
            report.put("notes", "Synthetic 1000 independent historical pairs spaced 72 hours apart; not dense GEM-124 throughput. "
                    + "Vector payload excludes protocol/metadata bytes. Heap peak sampled every 5ms during load only; "
                    + "pool peak sum is an upper bound, not a simultaneous heap observation.");
            Path path = Path.of("target/gem126-benchmark", mode + "-" + loaded.size() + (noop ? "-noop" : "-changed") + ".json");
            Files.createDirectories(path.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
            System.out.println("GEM126_VECTOR_REPORT " + path.toAbsolutePath());
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void seed(JdbcTemplate jdbc, int history) {
        jdbc.execute("ALTER TABLE story_clustering_versions DISABLE TRIGGER USER");
        jdbc.update("UPDATE story_clustering_versions SET status = CASE WHEN version_key = ? THEN 'ACTIVE' ELSE 'SHADOW' END", VERSION);
        jdbc.execute("ALTER TABLE story_clustering_versions ENABLE TRIGGER USER");
        long version = jdbc.queryForObject("SELECT id FROM story_clustering_versions WHERE version_key = ?", Long.class, VERSION);
        byte[] vector = ByteBuffer.allocate(DIMENSION * Float.BYTES).putFloat(1).array();
        long artifact = jdbc.queryForObject("""
                INSERT INTO story_embedding_artifacts (embedding_model_id, embedding_model_version,
                    embedding_dimension, title_normalization_version, title_input_hash, status,
                    vector_bytes, vector_hash, vector_norm, attempt_count, ready_at, created_at, updated_at)
                VALUES ('text-embedding-3-small', 'openai:text-embedding-3-small@2026-07-20',
                    1536, 'art031-title-nfkc-ws-v1', repeat('a', 64), 'READY', ?, ?, 1, 1, ?, ?, ?) RETURNING id
                """, Long.class, vector, StorySnapshotCanonicalizer.sha256(vector), ts(WATERMARK), ts(WATERMARK), ts(WATERMARK));
        jdbc.update("""
                INSERT INTO articles (canonical_url, url_hash, domain, first_seen_at, created_at, updated_at)
                SELECT 'https://vector-scale.example/' || n, repeat(md5('vector-article:' || n), 2),
                    'vector-scale.example', ?, ?, ? FROM generate_series(1, ?) n
                """, ts(WATERMARK), ts(WATERMARK), ts(WATERMARK), history + 2 * CHANGES);
        jdbc.update("""
                INSERT INTO story_article_inputs (clustering_version_id, article_id, article_ref,
                    effective_at, effective_at_source, normalized_title, title_input_hash, title_usability,
                    article_input_fingerprint, embedding_status, embedding_artifact_id,
                    attempt_count, current_marker, created_at)
                SELECT ?, article.id, article.url_hash,
                    ?::timestamptz - CASE
                        WHEN n <= 2000 THEN (((n - 1) % 1000 + 1) * interval '72 hours')
                        ELSE interval '10000 days' + (n * interval '25 hours') END,
                    'PUBLISHED_AT', CASE WHEN n <= 1000 THEN 'Synthetic changed article' ELSE 'Synthetic assigned article' END,
                    repeat('a', 64), 'USABLE', repeat(md5('vector-input:' || n), 2), 'READY', ?, 1, 1, ?
                FROM generate_series(1, ?) n
                JOIN articles article ON article.url_hash = repeat(md5('vector-article:' || n), 2)
                ORDER BY n
                """, version, ts(WATERMARK), artifact, ts(WATERMARK), history + 2 * CHANGES);
        jdbc.execute("ANALYZE story_article_inputs, story_embedding_artifacts");
    }

    private String semanticDigest(StoryPartitionService.Partition partition, StorySnapshotService.PairSearchResult result) {
        StringBuilder semantic = new StringBuilder(partition.components().toString());
        for (var decision : result.decisions()) {
            semantic.append('\n').append(decision.left().articleRef()).append(':').append(decision.right().articleRef())
                    .append(':').append(decision.similarity()).append(':').append(decision.timeDistanceSeconds())
                    .append(':').append(decision.rank()).append(':').append(decision.result())
                    .append(':').append(decision.triggeredRule()).append(':').append(decision.topOneBelowThreshold());
        }
        semantic.append(':').append(result.examinedPairs()).append(':').append(result.comparedPairs());
        return StorySnapshotCanonicalizer.sha256(semantic.toString().getBytes(StandardCharsets.UTF_8));
    }

    private long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private Timestamp ts(Instant instant) {
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
