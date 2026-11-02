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

    @Autowired
    StoryHnswShadowService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    StoryHnswShadowService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
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
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<TimedExact> exact = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                return new TimedExact(exactSearch.get(), elapsedMillis(started));
            }, executor);
            long hnswStarted = System.nanoTime();
            try {
                sync(version.id(), snapshot.id(), inputs);
                ShadowSearch shadow = search(version, inputs);
                long hnswMillis = elapsedMillis(hnswStarted);
                TimedExact timedExact = exact.join();
                StorySnapshotService.PairSearchResult exactResult = timedExact.result();
                StoryPartitionService.Partition exactPartition = StoryPartitionService
                        .partitionForPublication(rules, inputs, exactResult.decisions());
                StoryPartitionService.Partition shadowPartition = StoryPartitionService
                        .partitionForPublication(rules, inputs, shadow.decisions());
                Map<String, Object> difference = difference(exactPartition, shadowPartition);
                boolean samePartition = difference.isEmpty();
                double recall = shadow.expected() == 0 ? 1d
                        : (double) shadow.recalled() / shadow.expected();
                boolean activationSuggested = recall >= RECALL_TARGET && samePartition
                        && hnswMillis <= INTERVAL_MILLIS;
                save(run.id(), snapshot.id(), "SUCCEEDED", timedExact.millis(), hnswMillis,
                        shadow.expected(), shadow.recalled(), shadow.underfilled(), difference,
                        null, activationSuggested);
                return exactResult;
            } catch (RuntimeException exception) {
                TimedExact timedExact = exact.join();
                StorySnapshotService.PairSearchResult exactResult = timedExact.result();
                save(run.id(), snapshot.id(), "FAILED", timedExact.millis(), null,
                        0, 0, 0, Map.of(), exception.getMessage(), false);
                LOGGER.warn("HNSW shadow failed for snapshot={}", snapshot.id(), exception);
                return exactResult;
            }
        }
    }

    private void sync(long versionId, long snapshotId,
                      List<StorySnapshotRepository.SnapshotInput> inputs) {
        jdbc.update("DELETE FROM story_hnsw_shadow_embeddings WHERE clustering_version_id = ? AND snapshot_id <> ?",
                versionId, snapshotId);
        for (var input : inputs) {
            float[] vector = ExactCosine.decode(input.vectorBytes(), 1536, input.vectorHash());
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
                    input.vectorHash(), vectorLiteral(vector));
        }
        if (inputs.isEmpty()) {
            jdbc.update("DELETE FROM story_hnsw_shadow_embeddings WHERE clustering_version_id = ?", versionId);
        }
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM story_hnsw_shadow_embeddings WHERE clustering_version_id = ? AND snapshot_id = ?",
                Integer.class, versionId, snapshotId);
        if (count == null || count != inputs.size()) {
            throw new IllegalStateException("Incomplete HNSW shadow index");
        }
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
            List<Scored> exact = rerank(query, inputs, vectors, TOP_K);
            exact.forEach(candidate -> exactPairs.add(PairKey.of(query.articleRef(), candidate.input().articleRef())));
            List<StorySnapshotRepository.SnapshotInput> candidates = hnswCandidates(
                    version.id(), query, byRef, vectors.get(query.articleRef()));
            if (candidates.size() < Math.min(ANN_K, inputs.size() - 1)) underfilled++;
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
            long versionId, StorySnapshotRepository.SnapshotInput query,
            Map<String, StorySnapshotRepository.SnapshotInput> byRef, float[] vector) {
        return jdbc.execute((ConnectionCallback<List<StorySnapshotRepository.SnapshotInput>>) connection -> {
            try (var setting = connection.createStatement()) {
                setting.execute("SET hnsw.ef_search = " + EF_SEARCH);
            }
            try (var statement = connection.prepareStatement("""
                    SELECT article_ref FROM story_hnsw_shadow_embeddings
                    WHERE clustering_version_id = ? AND article_ref <> ?
                    ORDER BY embedding <=> ?::public.vector
                    LIMIT ?
                    """)) {
                statement.setLong(1, versionId);
                statement.setString(2, query.articleRef());
                statement.setString(3, vectorLiteral(vector));
                statement.setInt(4, ANN_K);
                try (var resultSet = statement.executeQuery()) {
                    List<StorySnapshotRepository.SnapshotInput> result = new ArrayList<>();
                    while (resultSet.next()) result.add(byRef.get(resultSet.getString(1)));
                    return result;
                }
            }
        });
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
                      Map<String, Object> difference, String error, boolean activationSuggested) {
        try {
            jdbc.update("""
                    INSERT INTO story_hnsw_shadow_runs (
                        story_run_id, snapshot_id, status, exact_milliseconds, hnsw_milliseconds,
                        expected_candidates, recalled_candidates, underfilled_articles,
                        partition_difference, error, activation_suggested, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    ON CONFLICT (story_run_id) DO UPDATE SET
                        status = EXCLUDED.status,
                        exact_milliseconds = EXCLUDED.exact_milliseconds,
                        hnsw_milliseconds = EXCLUDED.hnsw_milliseconds,
                        expected_candidates = EXCLUDED.expected_candidates,
                        recalled_candidates = EXCLUDED.recalled_candidates,
                        underfilled_articles = EXCLUDED.underfilled_articles,
                        partition_difference = EXCLUDED.partition_difference,
                        error = EXCLUDED.error,
                        activation_suggested = EXCLUDED.activation_suggested,
                        created_at = EXCLUDED.created_at
                    """, runId, snapshotId, status, exactMillis, hnswMillis, expected, recalled,
                    underfilled, objectMapper.writeValueAsString(difference), error,
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

    private record Scored(StorySnapshotRepository.SnapshotInput input, ExactCosine.Score score) { }
    private record PairKey(String left, String right) {
        static PairKey of(String first, String second) {
            return first.compareTo(second) < 0 ? new PairKey(first, second) : new PairKey(second, first);
        }
    }
    private record ShadowSearch(List<StorySnapshotService.PairDecision> decisions,
                                long expected, long recalled, long underfilled) { }
    private record TimedExact(StorySnapshotService.PairSearchResult result, long millis) { }
}
