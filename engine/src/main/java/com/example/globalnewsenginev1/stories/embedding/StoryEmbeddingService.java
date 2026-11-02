package com.example.globalnewsenginev1.stories.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StoryEmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoryEmbeddingService.class);
    private static final List<Duration> RETRY_BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(6));

    private final StoryEmbeddingRepository repository;
    private final StoryTitleNormalizer normalizer;
    private final EmbeddingClient client;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final StoryEmbeddingMetrics metrics;

    @Autowired
    StoryEmbeddingService(
            StoryEmbeddingRepository repository,
            StoryTitleNormalizer normalizer,
            EmbeddingClient client,
            PlatformTransactionManager transactionManager,
            StoryEmbeddingMetrics metrics
    ) {
        this(repository, normalizer, client, new TransactionTemplate(transactionManager),
                Clock.systemUTC(), metrics);
    }

    StoryEmbeddingService(
            StoryEmbeddingRepository repository,
            StoryTitleNormalizer normalizer,
            EmbeddingClient client,
            TransactionTemplate transactionTemplate,
            Clock clock,
            StoryEmbeddingMetrics metrics
    ) {
        this.repository = repository;
        this.normalizer = normalizer;
        this.client = client;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.metrics = metrics;
    }

    public ProcessingResult processIncremental(int batchSize) {
        return process(batchSize, false);
    }

    ProcessingResult processIncremental(String versionKey, boolean excludeVersion, int batchSize) {
        return process(repository.findCandidates(versionKey, excludeVersion, batchSize, false));
    }

    public ProcessingResult repair(int batchSize) {
        return process(batchSize, true);
    }

    ProcessingResult repair(String versionKey, int batchSize) {
        return process(repository.findCandidates(versionKey, false, batchSize, true));
    }

    public ProcessingResult processVersion(long versionId, int batchSize, Instant watermark) {
        List<StoryEmbeddingRepository.ArticleCandidate> candidates =
                repository.findCandidates(versionId, batchSize, true, watermark);
        return process(candidates);
    }

    private ProcessingResult process(int batchSize, boolean repairRun) {
        List<StoryEmbeddingRepository.ArticleCandidate> candidates =
                repository.findCandidates(batchSize, repairRun);
        return process(candidates);
    }

    private ProcessingResult process(List<StoryEmbeddingRepository.ArticleCandidate> candidates) {
        int processed = 0;
        int failed = 0;
        Map<BatchKey, List<StoryEmbeddingRepository.ArticleCandidate>> batches = new LinkedHashMap<>();
        for (StoryEmbeddingRepository.ArticleCandidate candidate : candidates) {
            batches.computeIfAbsent(new BatchKey(candidate.embeddingModelId(),
                    candidate.embeddingModelVersion(), candidate.embeddingDimension()),
                    ignored -> new ArrayList<>()).add(candidate);
        }
        for (List<StoryEmbeddingRepository.ArticleCandidate> batch : batches.values()) {
            try {
                Integer completed = transactionTemplate.execute(status -> processBatch(batch));
                if (completed != null) {
                    processed += completed;
                    for (int index = 0; index < completed; index++) metrics.processed();
                }
            } catch (RuntimeException exception) {
                failed += batch.size();
                LOGGER.error("Story title embedding batch failed for model={} dimension={} size={}",
                        batch.getFirst().embeddingModelId(), batch.getFirst().embeddingDimension(),
                        batch.size(), exception);
            }
        }
        return new ProcessingResult(candidates.size(), processed, failed);
    }

    private int processBatch(List<StoryEmbeddingRepository.ArticleCandidate> candidates) {
        List<PreparedCandidate> prepared = new ArrayList<>();
        Map<Long, ArtifactWork> workByArtifact = new LinkedHashMap<>();
        for (StoryEmbeddingRepository.ArticleCandidate candidate : candidates) {
            if (!repository.lockProcessingVersion(candidate.versionId())) continue;
            repository.lockArticle(candidate.articleId());
            TitleInput title = normalizer.normalize(candidate.title());
            StoryInputEligibilityRule.Decision eligibility = StoryInputEligibilityRule.evaluate(
                    candidate.inputEligibilityRuleVersion(), candidate.canonicalUrl(),
                    title.normalizedTitle());
            Instant effectiveAt = candidate.publishedAt() != null
                    ? candidate.publishedAt() : candidate.firstSeenAt();
            String effectiveAtSource = candidate.publishedAt() != null
                    ? "PUBLISHED_AT" : "FIRST_SEEN_AT";
            StoryEmbeddingRepository.Artifact artifact = null;
            if (title.usability() == TitleInput.TitleUsability.USABLE && !eligibility.excluded()) {
                long artifactId = repository.ensureArtifact(candidate, title.titleInputHash(), clock.instant());
                artifact = repository.lockArtifact(artifactId).orElse(null);
                if (artifact == null) continue;
                if (shouldRequest(artifact)) {
                    workByArtifact.putIfAbsent(artifact.id(), new ArtifactWork(candidate, title, artifact));
                }
            }
            prepared.add(new PreparedCandidate(candidate, title, eligibility, artifact,
                    effectiveAt, effectiveAtSource));
        }
        Map<Long, StoryEmbeddingRepository.Artifact> results = processArtifacts(
                candidates.getFirst().embeddingModelId(), candidates.getFirst().embeddingDimension(),
                new ArrayList<>(workByArtifact.values()));
        for (PreparedCandidate item : prepared) {
            StoryEmbeddingRepository.Artifact artifact = item.artifact() == null ? null
                    : results.getOrDefault(item.artifact().id(), item.artifact());
            String fingerprint = fingerprint(item.candidate(), item.title(), item.eligibility(),
                    artifact, item.effectiveAt(), item.effectiveAtSource());
            repository.synchronizeInput(item.candidate(), item.title(), item.eligibility(), artifact,
                    fingerprint, item.effectiveAt(), item.effectiveAtSource(), clock.instant());
        }
        return prepared.size();
    }

    private boolean shouldRequest(StoryEmbeddingRepository.Artifact artifact) {
        if ("READY".equals(artifact.status()) || "TERMINAL_FAILURE".equals(artifact.status())
                || artifact.attemptCount() >= 5 || !client.isAvailable()
                || (artifact.nextRetryAt() != null && artifact.nextRetryAt().isAfter(clock.instant()))) {
            return false;
        }
        return true;
    }

    private Map<Long, StoryEmbeddingRepository.Artifact> processArtifacts(
            String modelId, int dimension, List<ArtifactWork> work
    ) {
        Map<Long, StoryEmbeddingRepository.Artifact> results = new LinkedHashMap<>();
        if (work.isEmpty()) return results;
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        metrics.modelCall();
        work.forEach(ignored -> metrics.attempt());
        try {
            List<EmbeddingClient.EmbeddingResponse> responses = client.embed(modelId, dimension,
                    work.stream().map(item -> item.title().normalizedTitle()).toList());
            boolean[] seen = new boolean[work.size()];
            for (EmbeddingClient.EmbeddingResponse response : responses) {
                if (response.index() < 0 || response.index() >= work.size()
                        || seen[response.index()]) {
                    throw new EmbeddingClientException("INVALID_BATCH_INDEX",
                            "Provider returned invalid embedding index " + response.index(),
                            true, null, response.providerRequestId(), null);
                }
                seen[response.index()] = true;
            }
            if (responses.size() != work.size()) {
                throw new EmbeddingClientException("INCOMPLETE_BATCH",
                        "Provider returned %d of %d embeddings".formatted(responses.size(), work.size()),
                        true, null, null, null);
            }
            for (EmbeddingClient.EmbeddingResponse response : responses) {
                ArtifactWork item = work.get(response.index());
                results.put(item.artifact().id(), completeArtifact(item, response, startedAt));
            }
        } catch (EmbeddingClientException exception) {
            for (ArtifactWork item : work) {
                if (!results.containsKey(item.artifact().id())) {
                    results.put(item.artifact().id(), completeFailure(item.artifact(), exception, startedAt));
                }
            }
        } finally {
            metrics.recordLatency(System.nanoTime() - startedNanos);
        }
        return results;
    }

    private StoryEmbeddingRepository.Artifact completeArtifact(
            ArtifactWork item, EmbeddingClient.EmbeddingResponse response, Instant startedAt
    ) {
        StoryEmbeddingRepository.Artifact artifact = item.artifact();
        int attempt = artifact.attemptCount() + 1;
        try {
            EmbeddingVectorCodec.EncodedVector encoded = EmbeddingVectorCodec.encode(
                    response.vector(), item.candidate().embeddingDimension());
            Instant completedAt = clock.instant();
            repository.completeReady(artifact.id(), attempt, encoded, response.providerRequestId(),
                    response.vector().length, startedAt, completedAt);
            return new StoryEmbeddingRepository.Artifact(
                    artifact.id(), "READY", encoded.hash(), attempt, null);
        } catch (EmbeddingVectorCodec.InvalidVectorException exception) {
            Instant completedAt = clock.instant();
            repository.completeFailure(artifact.id(), attempt, "TERMINAL_FAILURE",
                    response.providerRequestId(), exception.code(), exception.getMessage(),
                    response.vector().length, startedAt, completedAt, null);
            metrics.failure();
            return new StoryEmbeddingRepository.Artifact(
                    artifact.id(), "TERMINAL_FAILURE", null, attempt, null);
        }
    }

    private StoryEmbeddingRepository.Artifact completeFailure(
            StoryEmbeddingRepository.Artifact artifact,
            EmbeddingClientException exception,
            Instant startedAt
    ) {
        int attempt = artifact.attemptCount() + 1;
        Instant completedAt = clock.instant();
        Instant nextRetry = exception.retryable()
                ? nextRetryAt(attempt, completedAt, exception.retryAfter()) : null;
        String status = exception.retryable() ? "RETRYABLE_FAILURE" : "TERMINAL_FAILURE";
        repository.completeFailure(artifact.id(), attempt, status, exception.providerRequestId(),
                exception.errorCode(), exception.getMessage(), null, startedAt, completedAt, nextRetry);
        metrics.failure();
        return new StoryEmbeddingRepository.Artifact(artifact.id(), status, null, attempt, nextRetry);
    }

    private String fingerprint(
            StoryEmbeddingRepository.ArticleCandidate candidate,
            TitleInput title,
            StoryInputEligibilityRule.Decision eligibility,
            StoryEmbeddingRepository.Artifact artifact,
            Instant effectiveAt,
            String effectiveAtSource
    ) {
        String canonical = String.join("\n",
                candidate.articleRef(),
                effectiveAt.toString(),
                effectiveAtSource,
                title.usability().name(),
                eligibility.disposition(),
                value(eligibility.reason()),
                value(title.titleInputHash()),
                candidate.titleNormalizationVersion(),
                candidate.genericTitleRuleVersion(),
                candidate.inputEligibilityRuleVersion(),
                candidate.embeddingModelId(),
                candidate.embeddingModelVersion(),
                Integer.toString(candidate.embeddingDimension()),
                artifact == null ? "" : Long.toString(artifact.id()),
                artifact == null ? "" : value(artifact.vectorHash()));
        return StoryTitleNormalizer.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    static Instant nextRetryAt(int attempt, Instant completedAt, Duration retryAfter) {
        if (attempt >= 5) {
            return null;
        }
        return completedAt.plus(retryAfter != null ? retryAfter : RETRY_BACKOFF.get(attempt - 1));
    }

    public record ProcessingResult(int selected, int processed, int failed) {
    }

    private record BatchKey(String modelId, String modelVersion, int dimension) {
    }

    private record PreparedCandidate(
            StoryEmbeddingRepository.ArticleCandidate candidate,
            TitleInput title,
            StoryInputEligibilityRule.Decision eligibility,
            StoryEmbeddingRepository.Artifact artifact,
            Instant effectiveAt,
            String effectiveAtSource
    ) {
    }

    private record ArtifactWork(
            StoryEmbeddingRepository.ArticleCandidate candidate,
            TitleInput title,
            StoryEmbeddingRepository.Artifact artifact
    ) {
    }
}
