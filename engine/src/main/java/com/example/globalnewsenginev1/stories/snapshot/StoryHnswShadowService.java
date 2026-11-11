package com.example.globalnewsenginev1.stories.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(name = "stories.hnsw-shadow.enabled", havingValue = "true", matchIfMissing = true)
class StoryHnswShadowService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoryHnswShadowService.class);
    private static final int TOP_K = 50;
    private static final int ANN_K = 200;
    private static final int EF_SEARCH = 400;
    private static final double RECALL_TARGET = 0.98;
    private static final long INTERVAL_MILLIS = Duration.ofMinutes(5).toMillis();

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    @Autowired
    StoryHnswShadowService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, Clock.systemUTC());
    }

    StoryHnswShadowService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    StorySnapshotService.PairSearchResult run(
            StorySnapshotRepository.ClusteringVersion version,
            StorySnapshotRepository.Snapshot snapshot,
            StorySnapshotRepository.RunClaim run,
            List<StorySnapshotRepository.SnapshotInput> inputs,
            StoryPartitionService.SnapshotRules rules,
            Supplier<StorySnapshotService.PairSearchResult> exactSearch
    ) {
        if (version.dimension() != 1536 || version.windowHours() != 24) {
            return exactSearch.get();
        }
        List<StorySnapshotRepository.SnapshotInput> windowInputs = currentWindowInputs(
                snapshot.watermark(), inputs, version.windowHours());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Attempt<StorySnapshotService.PairSearchResult>> exact =
                    CompletableFuture.supplyAsync(() -> attempt(exactSearch), executor);
            Attempt<ShadowSearch> hnsw = attempt(() -> {
                sync(version.id(), snapshot.id(), windowInputs);
                return search(version, windowInputs);
            });
            Attempt<StorySnapshotService.PairSearchResult> exactResult = exact.join();
            if (exactResult.error() != null) {
                save(run.id(), snapshot.id(), "FAILED", exactResult.millis(), hnsw.millis(),
                        0, 0, 0, Map.of(), exactResult.error().getMessage(),
                        hnsw.errorMessage(), false);
                throw exactResult.error();
            }
            if (hnsw.error() != null) {
                save(run.id(), snapshot.id(), "FAILED", exactResult.millis(), hnsw.millis(),
                        0, 0, 0, Map.of(), null, hnsw.errorMessage(), false);
                LOGGER.warn("HNSW shadow failed for snapshot={}", snapshot.id(), hnsw.error());
                return exactResult.result();
            }
            try {
                Set<String> windowRefs = windowInputs.stream()
                        .map(StorySnapshotRepository.SnapshotInput::articleRef)
                        .collect(java.util.stream.Collectors.toSet());
                List<StorySnapshotService.PairDecision> exactWindowDecisions = exactResult.result().decisions().stream()
                        .filter(decision -> windowRefs.contains(decision.left().articleRef())
                                && windowRefs.contains(decision.right().articleRef()))
                        .toList();
                StoryPartitionService.Partition exactPartition = StoryPartitionService
                        .partitionForPublication(rules, windowInputs, exactWindowDecisions);
                StoryPartitionService.Partition shadowPartition = StoryPartitionService
                        .partitionForPublication(rules, windowInputs, hnsw.result().decisions());
                Map<String, Object> difference = difference(exactPartition, shadowPartition);
                boolean samePartition = difference.isEmpty();
                double recall = hnsw.result().expected() == 0 ? 1d
                        : (double) hnsw.result().recalled() / hnsw.result().expected();
                boolean activationSuggested = recall >= RECALL_TARGET && samePartition
                        && hnsw.millis() <= INTERVAL_MILLIS;
                save(run.id(), snapshot.id(), "SUCCEEDED", exactResult.millis(), hnsw.millis(),
                        hnsw.result().expected(), hnsw.result().recalled(), hnsw.result().underfilled(), difference,
                        null, null, activationSuggested);
                return exactResult.result();
            } catch (RuntimeException exception) {
                save(run.id(), snapshot.id(), "FAILED", exactResult.millis(), hnsw.millis(),
                        0, 0, 0, Map.of(), null, exception.getMessage(), false);
                LOGGER.warn("HNSW shadow failed for snapshot={}", snapshot.id(), exception);
                return exactResult.result();
            }
        }
    }

    private void sync(long versionId, long snapshotId,
                      List<StorySnapshotRepository.SnapshotInput> inputs) {
        List<float[]> vectors = inputs.stream()
                .map(input -> ExactCosine.decode(input.vectorBytes(), 1536, input.vectorHash()))
                .toList();
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < inputs.size(); i++) {
                var input = inputs.get(i);
                jdbc.update("""
                    INSERT INTO story_hnsw_shadow_embeddings (
                        clustering_version_id, article_ref, snapshot_id, effective_at, vector_hash, embedding
                    ) VALUES (?, ?, ?, ?, ?, ?::public.vector)
                    ON CONFLICT (clustering_version_id, article_ref) DO UPDATE SET
                        snapshot_id = EXCLUDED.snapshot_id,
                        effective_at = EXCLUDED.effective_at,
                        vector_hash = EXCLUDED.vector_hash,
                        embedding = EXCLUDED.embedding
                    """, versionId, input.articleRef(), snapshotId, Timestamp.from(input.effectiveAt()),
                        input.vectorHash(), vectorLiteral(vectors.get(i)));
            }
            jdbc.update("DELETE FROM story_hnsw_shadow_embeddings WHERE clustering_version_id = ? AND snapshot_id <> ?",
                    versionId, snapshotId);
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM story_hnsw_shadow_embeddings WHERE clustering_version_id = ? AND snapshot_id = ?",
                    Integer.class, versionId, snapshotId);
            if (count == null || count != inputs.size()) {
                throw new IllegalStateException("Incomplete HNSW shadow index");
            }
        });
        jdbc.execute("ANALYZE story_hnsw_shadow_embeddings");
    }

    private ShadowSearch search(StorySnapshotRepository.ClusteringVersion version,
                                List<StorySnapshotRepository.SnapshotInput> inputs) {
        Map<String, StorySnapshotRepository.SnapshotInput> byRef = new HashMap<>();
        Map<String, float[]> vectors = new HashMap<>();
        for (var input : inputs) {
            byRef.put(input.articleRef(), input);
            vectors.put(input.articleRef(), ExactCosine.decode(
                    input.vectorBytes(), version.dimension(), input.vectorHash()));
        }
        Set<PairKey> exactPairs = new HashSet<>();
        Set<PairKey> hnswPairs = new HashSet<>();
        Map<PairKey, StorySnapshotService.PairDecision> decisions = new HashMap<>();
        long underfilled = 0;
        for (var query : inputs) {
            List<StorySnapshotRepository.SnapshotInput> windowInputs =
                    candidatesInWindow(query, inputs, version.windowHours());
            List<Scored> exact = rerank(query, windowInputs, vectors, TOP_K);
            exact.forEach(candidate -> exactPairs.add(PairKey.of(query.articleRef(), candidate.input().articleRef())));
            List<StorySnapshotRepository.SnapshotInput> candidates = hnswCandidates(
                    version.id(), version.windowHours(), query, byRef, vectors.get(query.articleRef()));
            if (candidates.size() < Math.min(ANN_K, windowInputs.size() - 1)) underfilled++;
            List<Scored> reranked = rerank(query, candidates, vectors, TOP_K);
            for (Scored candidate : reranked) {
                PairKey key = PairKey.of(query.articleRef(), candidate.input().articleRef());
                hnswPairs.add(key);
                if (candidate.score().quantized().compareTo(version.threshold()) >= 0) {
                    var left = byRef.get(key.left());
                    var right = byRef.get(key.right());
                    decisions.put(key, new StorySnapshotService.PairDecision(left, right,
                            candidate.score().quantized(), Duration.between(
                            left.effectiveAt(), right.effectiveAt()).abs().toSeconds(), 0,
                            "SAME_STORY", version.pairRuleVersion(), false));
                }
            }
        }
        List<StorySnapshotService.PairDecision> ordered = new ArrayList<>(decisions.values());
        ordered.sort(Comparator.comparing(StorySnapshotService.PairDecision::similarity).reversed()
                .thenComparing(decision -> decision.left().articleRef())
                .thenComparing(decision -> decision.right().articleRef()));
        for (int i = 0; i < ordered.size(); i++) {
            var decision = ordered.get(i);
            ordered.set(i, new StorySnapshotService.PairDecision(decision.left(), decision.right(),
                    decision.similarity(), decision.timeDistanceSeconds(), i + 1,
                    decision.result(), decision.triggeredRule(), decision.topOneBelowThreshold()));
        }
        long recalled = exactPairs.stream().filter(hnswPairs::contains).count();
        return new ShadowSearch(List.copyOf(ordered), exactPairs.size(), recalled, underfilled);
    }

    private List<StorySnapshotRepository.SnapshotInput> hnswCandidates(
            long versionId, int windowHours, StorySnapshotRepository.SnapshotInput query,
            Map<String, StorySnapshotRepository.SnapshotInput> byRef, float[] vector) {
        return jdbc.execute((ConnectionCallback<List<StorySnapshotRepository.SnapshotInput>>) connection -> {
            try (var setting = connection.createStatement()) {
                setting.execute("SET hnsw.ef_search = " + EF_SEARCH);
            }
            try (var statement = connection.prepareStatement("""
                    SELECT article_ref FROM story_hnsw_shadow_embeddings
                    WHERE clustering_version_id = ? AND article_ref <> ?
                      AND effective_at BETWEEN ? AND ?
                    ORDER BY embedding OPERATOR(public.<=>) ?::public.vector
                    LIMIT ?
                    """)) {
                statement.setLong(1, versionId);
                statement.setString(2, query.articleRef());
                statement.setTimestamp(3, Timestamp.from(query.effectiveAt().minus(Duration.ofHours(windowHours))));
                statement.setTimestamp(4, Timestamp.from(query.effectiveAt().plus(Duration.ofHours(windowHours))));
                statement.setString(5, vectorLiteral(vector));
                statement.setInt(6, ANN_K);
                try (var resultSet = statement.executeQuery()) {
                    List<StorySnapshotRepository.SnapshotInput> result = new ArrayList<>();
                    while (resultSet.next()) result.add(byRef.get(resultSet.getString(1)));
                    return result;
                }
            }
        });
    }

    static List<StorySnapshotRepository.SnapshotInput> candidatesInWindow(
            StorySnapshotRepository.SnapshotInput query,
            List<StorySnapshotRepository.SnapshotInput> inputs,
            int windowHours) {
        Duration window = Duration.ofHours(windowHours);
        Instant start = query.effectiveAt().minus(window);
        Instant end = query.effectiveAt().plus(window);
        int from = 0;
        int to = inputs.size();
        while (from < to) {
            int middle = (from + to) >>> 1;
            if (inputs.get(middle).effectiveAt().isBefore(start)) from = middle + 1;
            else to = middle;
        }
        int until = from;
        to = inputs.size();
        while (until < to) {
            int middle = (until + to) >>> 1;
            if (inputs.get(middle).effectiveAt().isAfter(end)) to = middle;
            else until = middle + 1;
        }
        return inputs.subList(from, until);
    }

    static List<StorySnapshotRepository.SnapshotInput> currentWindowInputs(
            Instant watermark,
            List<StorySnapshotRepository.SnapshotInput> inputs,
            int windowHours) {
        Instant start = watermark.minus(Duration.ofHours(windowHours));
        int from = 0;
        int to = inputs.size();
        while (from < to) {
            int middle = (from + to) >>> 1;
            if (inputs.get(middle).effectiveAt().isBefore(start)) from = middle + 1;
            else to = middle;
        }
        int until = from;
        while (until < inputs.size() && !inputs.get(until).effectiveAt().isAfter(watermark)) until++;
        return inputs.subList(from, until);
    }

    private static List<Scored> rerank(StorySnapshotRepository.SnapshotInput query,
                                       List<StorySnapshotRepository.SnapshotInput> candidates,
                                       Map<String, float[]> vectors, int limit) {
        return candidates.stream()
                .filter(candidate -> !candidate.articleRef().equals(query.articleRef()))
                .map(candidate -> new Scored(candidate, ExactCosine.score(
                        vectors.get(query.articleRef()), vectors.get(candidate.articleRef()))))
                .sorted(Comparator.comparing((Scored candidate) -> candidate.score().exact()).reversed()
                        .thenComparing(candidate -> candidate.input().articleRef()))
                .limit(limit).toList();
    }

    private Map<String, Object> difference(StoryPartitionService.Partition exact,
                                           StoryPartitionService.Partition shadow) {
        Map<String, String> exactByMember = canonical(exact);
        Map<String, String> shadowByMember = canonical(shadow);
        Map<String, Object> difference = new LinkedHashMap<>();
        Set<String> refs = new java.util.TreeSet<>(exactByMember.keySet());
        refs.addAll(shadowByMember.keySet());
        for (String ref : refs) {
            if (!java.util.Objects.equals(exactByMember.get(ref), shadowByMember.get(ref))) {
                difference.put(ref, Map.of("exact", exactByMember.get(ref),
                        "hnsw", shadowByMember.get(ref)));
            }
        }
        return difference;
    }

    private static Map<String, String> canonical(StoryPartitionService.Partition partition) {
        Map<String, String> result = new HashMap<>();
        for (var component : partition.components()) {
            String members = component.members().stream().map(StoryPartitionService.MemberEvidence::articleRef)
                    .sorted().reduce((left, right) -> left + "," + right).orElse("");
            String value = component.medoidArticleRef() + ":" + members;
            component.members().forEach(member -> result.put(member.articleRef(), value));
        }
        return result;
    }

    private void save(long runId, long snapshotId, String status, long exactMillis,
                      Long hnswMillis, long expected, long recalled, long underfilled,
                      Map<String, Object> difference, String exactError, String hnswError,
                      boolean activationSuggested) {
        try {
            jdbc.update("""
                    INSERT INTO story_hnsw_shadow_runs (
                        story_run_id, snapshot_id, status, exact_milliseconds, hnsw_milliseconds,
                        expected_candidates, recalled_candidates, underfilled_articles,
                        partition_difference, error, exact_error, hnsw_error,
                        activation_suggested, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    ON CONFLICT (story_run_id) DO UPDATE SET
                        status = EXCLUDED.status,
                        exact_milliseconds = EXCLUDED.exact_milliseconds,
                        hnsw_milliseconds = EXCLUDED.hnsw_milliseconds,
                        expected_candidates = EXCLUDED.expected_candidates,
                        recalled_candidates = EXCLUDED.recalled_candidates,
                        underfilled_articles = EXCLUDED.underfilled_articles,
                        partition_difference = EXCLUDED.partition_difference,
                        error = EXCLUDED.error,
                        exact_error = EXCLUDED.exact_error,
                        hnsw_error = EXCLUDED.hnsw_error,
                        activation_suggested = EXCLUDED.activation_suggested,
                        created_at = EXCLUDED.created_at
                    """, runId, snapshotId, status, exactMillis, hnswMillis, expected, recalled,
                    underfilled, objectMapper.writeValueAsString(difference),
                    exactError != null ? exactError : hnswError, exactError, hnswError,
                    activationSuggested, Timestamp.from(clock.instant()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize HNSW shadow result", exception);
        }
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder value = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) value.append(',');
            value.append(Float.toString(vector[i]));
        }
        return value.append(']').toString();
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static <T> Attempt<T> attempt(Supplier<T> operation) {
        long started = System.nanoTime();
        try {
            return new Attempt<>(operation.get(), elapsedMillis(started), null);
        } catch (RuntimeException error) {
            return new Attempt<>(null, elapsedMillis(started), error);
        }
    }

    private record Scored(StorySnapshotRepository.SnapshotInput input, ExactCosine.Score score) { }
    private record PairKey(String left, String right) {
        static PairKey of(String first, String second) {
            return first.compareTo(second) < 0 ? new PairKey(first, second) : new PairKey(second, first);
        }
    }
    private record ShadowSearch(List<StorySnapshotService.PairDecision> decisions,
                                long expected, long recalled, long underfilled) { }
    private record Attempt<T>(T result, long millis, RuntimeException error) {
        String errorMessage() {
            return error == null ? null : error.getMessage();
        }
    }
}
