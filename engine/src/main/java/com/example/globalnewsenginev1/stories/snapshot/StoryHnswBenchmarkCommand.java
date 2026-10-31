package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "stories.hnsw-benchmark.enabled", havingValue = "true")
class StoryHnswBenchmarkCommand implements ApplicationRunner {
    private static final int DIMENSIONS = 1536;
    private static final int TOP_K = 50;
    private static final int ANN_FETCH_K = 200;
    private static final int WINDOW_HOURS = 24;
    private static final int EF_SEARCH = 400;
    private static final double RECALL_TARGET = 0.98;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    StoryHnswBenchmarkCommand(JdbcTemplate jdbc,
                              Environment environment, ConfigurableApplicationContext context) {
        this.jdbc = jdbc;
        this.objectMapper = new ObjectMapper();
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        try {
            int sampleSize = environment.getProperty("stories.hnsw-benchmark.sample-size", Integer.class, 100);
            Path output = Path.of(environment.getProperty("stories.hnsw-benchmark.output",
                    "../docs/analysis/GEM-080-pgvector-hnsw-results.json"));
            ObjectNode result = benchmark(sampleSize);
            Files.writeString(output, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n");
        } finally {
            context.close();
        }
    }

    ObjectNode benchmark(int sampleSize) {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("DROP TABLE IF EXISTS story_embedding_hnsw_shadow");
        jdbc.execute("CREATE UNLOGGED TABLE story_embedding_hnsw_shadow ("
                + "article_ref varchar(64) PRIMARY KEY, effective_at timestamptz NOT NULL, "
                + "embedding vector(1536) NOT NULL)");

        Instant watermark = jdbc.queryForObject("""
                WITH current_inputs AS (
                    SELECT DISTINCT ON (article_ref) article_ref, effective_at
                    FROM story_article_inputs
                    WHERE embedding_status = 'READY' AND current_marker = 1
                    ORDER BY article_ref, clustering_version_id DESC
                ), windows AS (
                    SELECT effective_at, COUNT(*) OVER (
                        ORDER BY effective_at
                        RANGE BETWEEN INTERVAL '24 hours' PRECEDING AND CURRENT ROW
                    ) AS article_count
                    FROM current_inputs
                )
                SELECT effective_at FROM windows
                ORDER BY article_count DESC, effective_at DESC
                LIMIT 1
                """, Timestamp.class).toInstant();
        int totalArticleCount = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT article_ref) FROM story_article_inputs
                WHERE embedding_status = 'READY' AND current_marker = 1
                """, Integer.class);
        List<Input> inputs = loadInputs(watermark);
        long loadStarted = System.nanoTime();
        for (int offset = 0; offset < inputs.size(); offset += 100) {
            List<Input> batch = inputs.subList(offset, Math.min(offset + 100, inputs.size()));
            jdbc.batchUpdate("INSERT INTO story_embedding_hnsw_shadow VALUES (?, ?, ?::vector)", batch,
                    batch.size(), (statement, input) -> {
                        statement.setString(1, input.articleRef());
                        statement.setTimestamp(2, Timestamp.from(input.effectiveAt()));
                        statement.setString(3, vectorLiteral(input.vector()));
                    });
        }
        long loadMillis = elapsedMillis(loadStarted);
        long indexStarted = System.nanoTime();
        jdbc.execute("CREATE INDEX story_embedding_hnsw_shadow_idx ON story_embedding_hnsw_shadow "
                + "USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 128)");
        jdbc.execute("ANALYZE story_embedding_hnsw_shadow");
        long indexBuildMillis = elapsedMillis(indexStarted);

        Map<String, Input> byRef = new HashMap<>();
        inputs.forEach(input -> byRef.put(input.articleRef(), input));
        List<Input> sample = inputs.stream()
                .sorted(Comparator.comparing(input -> StorySnapshotCanonicalizer.sha256(input.articleRef().getBytes())))
                .limit(Math.min(sampleSize, inputs.size()))
                .toList();

        long exactNanos = 0;
        long annNanos = 0;
        int expected = 0;
        int found = 0;
        int minimumCandidates = TOP_K;
        ArrayNode queries = objectMapper.createArrayNode();
        for (Input query : sample) {
            long exactStarted = System.nanoTime();
            List<Scored> exact = rerank(query, inputs.stream()
                    .filter(candidate -> !candidate.articleRef().equals(query.articleRef())).toList());
            exactNanos += System.nanoTime() - exactStarted;

            long annStarted = System.nanoTime();
            List<Input> candidates = jdbc.execute((ConnectionCallback<List<Input>>) connection -> {
                try (var setting = connection.createStatement()) {
                    setting.execute("SET hnsw.ef_search = " + EF_SEARCH);
                }
                try (var statement = connection.prepareStatement("""
                        SELECT article_ref FROM story_embedding_hnsw_shadow
                        ORDER BY embedding <=> ?::vector LIMIT ?
                        """)) {
                    statement.setString(1, vectorLiteral(query.vector()));
                    statement.setInt(2, ANN_FETCH_K + 1);
                    try (var resultSet = statement.executeQuery()) {
                        List<Input> result = new ArrayList<>(ANN_FETCH_K + 1);
                        while (resultSet.next()) result.add(byRef.get(resultSet.getString(1)));
                        return result;
                    }
                }
            }).stream()
                    .filter(candidate -> !candidate.articleRef().equals(query.articleRef()))
                    .limit(ANN_FETCH_K)
                    .toList();
            List<Scored> ann = rerank(query, candidates);
            annNanos += System.nanoTime() - annStarted;

            int hits = (int) ann.stream().filter(exact::contains).count();
            expected += exact.size();
            found += hits;
            minimumCandidates = Math.min(minimumCandidates, candidates.size());
            queries.add(objectMapper.createObjectNode()
                    .put("articleRef", query.articleRef())
                    .put("eligible", exact.size())
                    .put("returned", candidates.size())
                    .put("hits", hits));
        }

        double recall = expected == 0 ? 1 : (double) found / expected;
        Long indexBytes = jdbc.queryForObject(
                "SELECT pg_relation_size('story_embedding_hnsw_shadow_idx')", Long.class);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("measuredAt", Instant.now().toString());
        root.put("totalArticleCount", totalArticleCount);
        root.put("windowArticleCount", inputs.size());
        root.put("windowStart", watermark.minus(Duration.ofHours(WINDOW_HOURS)).toString());
        root.put("watermark", watermark.toString());
        root.put("windowSelection", "densest-24h");
        root.put("dimensions", DIMENSIONS);
        root.put("windowHours", WINDOW_HOURS);
        root.put("topK", TOP_K);
        root.put("annFetchK", ANN_FETCH_K);
        root.put("sampleSize", sample.size());
        root.put("recallTarget", RECALL_TARGET);
        root.put("recall", recall);
        root.put("targetMet", recall >= RECALL_TARGET);
        root.put("minimumCandidatesReturned", minimumCandidates);
        root.put("loadMilliseconds", loadMillis);
        root.put("indexBuildMilliseconds", indexBuildMillis);
        root.put("exactSearchMilliseconds", exactNanos / 1_000_000d);
        root.put("hnswSearchMilliseconds", annNanos / 1_000_000d);
        root.put("indexBytes", indexBytes == null ? 0 : indexBytes);
        root.putObject("hnsw").put("m", 16).put("efConstruction", 128).put("efSearch", EF_SEARCH)
                .put("timePrefiltered", true);
        root.set("queries", queries);
        return root;
    }

    private List<Input> loadInputs(Instant watermark) {
        return jdbc.query("""
                SELECT DISTINCT ON (input.article_ref) input.article_ref, input.effective_at,
                       artifact.vector_bytes, artifact.vector_hash
                FROM story_article_inputs input
                JOIN story_embedding_artifacts artifact ON artifact.id = input.embedding_artifact_id
                WHERE input.embedding_status = 'READY' AND input.current_marker = 1
                  AND artifact.embedding_dimension = 1536
                  AND input.effective_at BETWEEN ? AND ?
                ORDER BY input.article_ref, input.clustering_version_id DESC
                """, (rs, row) -> new Input(rs.getString(1), rs.getTimestamp(2).toInstant(),
                ExactCosine.decode(rs.getBytes(3), DIMENSIONS, rs.getString(4))),
                Timestamp.from(watermark.minus(Duration.ofHours(WINDOW_HOURS))),
                Timestamp.from(watermark));
    }

    static List<Scored> rerank(Input query, List<Input> candidates) {
        return candidates.stream()
                .map(candidate -> new Scored(candidate.articleRef(),
                        ExactCosine.score(query.vector(), candidate.vector()).exact()))
                .sorted(Comparator.comparingDouble(Scored::similarity).reversed()
                        .thenComparing(Scored::articleRef))
                .limit(TOP_K)
                .toList();
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder value = new StringBuilder(vector.length * 12).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) value.append(',');
            value.append(Float.toString(vector[index]));
        }
        return value.append(']').toString();
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    record Input(String articleRef, Instant effectiveAt, float[] vector) {
    }

    record Scored(String articleRef, double similarity) {
    }
}
