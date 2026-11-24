package com.example.globalnewsenginev1.stories.snapshot;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Publishes inside the caller's transaction, including its pair decisions and run completion. */
@Service
class StoryPublisher {
    private final JdbcTemplate jdbc;
    private final StorySnapshotRepository repository;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    private final StoryLocationMaterializer locationMaterializer;

    StoryPublisher(JdbcTemplate jdbc, StorySnapshotRepository repository,
                   PlatformTransactionManager manager, MeterRegistry metrics,
                   StoryLocationMaterializer locationMaterializer) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.metrics = metrics;
        this.locationMaterializer = locationMaterializer;
    }

    Plan prepare(StorySnapshotRepository.Snapshot snapshot) {
        return prepare(snapshot, StoryPartitionService.partition(repository.loadPartitionRules(snapshot.id()),
                repository.loadSnapshotInputs(snapshot.id()), false));
    }

    Plan prepare(StorySnapshotRepository.Snapshot snapshot,
                 StoryPartitionService.Partition partition) {
        return prepare(snapshot, partition, null);
    }

    Plan prepare(StorySnapshotRepository.Snapshot snapshot,
                 StoryPartitionService.Partition partition,
                 Set<String> processedArticleRefs) {
        Set<String> affectedRefs = new HashSet<>();
        partition.components().forEach(component -> component.members().forEach(member ->
                affectedRefs.add(member.articleRef())));
        if (processedArticleRefs != null) affectedRefs.addAll(processedArticleRefs);
        // Read the identity basis consistently; expensive clustering does not hold the publisher lock.
        Basis basis = transaction.execute(status -> {
            repository.lockVersion(snapshot.versionId());
            Set<UUID> scope = processedArticleRefs == null ? null : storyIds(snapshot.versionId(), affectedRefs);
            return new Basis(stories(snapshot.versionId(), scope), memberships(snapshot.versionId(), scope),
                    latestCommit(snapshot.versionId()));
        });
        basis.memberships().forEach(member -> affectedRefs.add(member.ref()));
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("snapshot", snapshot.id());
        parameters.put("refs", affectedRefs.isEmpty() ? Set.of("") : affectedRefs);
        List<Input> inputs = new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT input.id, input.article_id, member.article_ref,
                       member.article_input_fingerprint, input.effective_at, input.created_at,
                       input.title_usability
                FROM story_snapshot_members member
                JOIN story_article_inputs input ON input.id = member.article_input_id
                WHERE member.snapshot_id = :snapshot
                """ + (processedArticleRefs == null ? "" : " AND member.article_ref IN (:refs)")
                + " ORDER BY input.effective_at, member.article_ref", parameters, (rs, row) -> new Input(rs.getLong("id"), rs.getLong("article_id"),
                rs.getString("article_ref"), rs.getString("article_input_fingerprint"),
                rs.getTimestamp("effective_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getString("title_usability")));
        Set<String> processed = processedArticleRefs == null
                ? inputs.stream().map(Input::ref).collect(java.util.stream.Collectors.toSet())
                : Set.copyOf(processedArticleRefs);
        return new Plan(snapshot, basis, inputs, partition, processed, processedArticleRefs != null);
    }

    Result publish(Plan plan, StorySnapshotRepository.RunClaim run, Instant now, Duration lease) {
        var snapshot = plan.snapshot();
        repository.lockVersion(snapshot.versionId());
        assertLease(snapshot.versionId(), run, now, lease);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM story_publish_commits
                    WHERE clustering_version_id = ? AND snapshot_id = ?)
                """, Boolean.class, snapshot.versionId(), snapshot.id()))) {
            return new Result("REUSED", 0, plan.processedArticleRefs().size(), Map.of());
        }
        boolean stale = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM story_publish_commits commit
                    JOIN story_snapshots snapshot ON snapshot.id = commit.snapshot_id
                    WHERE commit.clustering_version_id = ?
                      AND (snapshot.snapshot_watermark > ?
                        OR (snapshot.snapshot_watermark = ? AND snapshot.id > ?)))
                """, Boolean.class, snapshot.versionId(), ts(snapshot.watermark()),
                ts(snapshot.watermark()), snapshot.id()));
        if (stale) {
            return new Result("DISCARDED", 0, plan.processedArticleRefs().size(), Map.of());
        }
        Set<UUID> scope = plan.incremental() ? plan.basis().stories().stream()
                .map(Story::id).collect(java.util.stream.Collectors.toSet()) : null;
        if (latestCommit(snapshot.versionId()) != plan.basis().commitId()
                || !stories(snapshot.versionId(), scope).equals(plan.basis().stories())
                || !memberships(snapshot.versionId(), scope).equals(plan.basis().memberships())) {
            throw conflict("Published story basis changed during calculation");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT article_ref FROM story_memberships
                    WHERE clustering_version_id = ? AND current_marker = 1
                    EXCEPT
                    SELECT article_ref FROM story_snapshot_members WHERE snapshot_id = ?
                )
                """, Boolean.class, snapshot.versionId(), snapshot.id()))) {
            throw conflict("Snapshot omits a current member; create a fresh snapshot");
        }

        Map<String, Input> inputs = new LinkedHashMap<>();
        plan.inputs().forEach(input -> inputs.put(input.ref(), input));
        Map<String, Membership> previous = new HashMap<>();
        plan.basis().memberships().forEach(member -> previous.put(member.ref(), member));
        List<StoryPartitionService.Component> components = plan.partition().components();
        Map<String, Integer> componentByArticle = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (var member : components.get(i).members()) {
                componentByArticle.put(member.articleRef(), i);
            }
        }
        var identity = StoryIdentity.match(plan.basis().stories(), plan.basis().memberships(), componentByArticle);
        Map<Integer, List<Story>> nominees = identity.nominees();
        Map<String, Long> counts = new LinkedHashMap<>();
        if (identity.splits() > 0) counts.put("split", (long) identity.splits());
        Map<String, UUID> assignments = new HashMap<>();
        Map<String, StoryPartitionService.MemberEvidence> evidence = new HashMap<>();
        Map<String, String> medoids = new HashMap<>();
        List<UUID> surviving = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            var component = components.get(i);
            List<Story> candidates = nominees.getOrDefault(i, List.of());
            Story old = StoryIdentity.winner(candidates);
            String firstRef = component.members().getFirst().articleRef();
            UUID id = old == null ? UUID.nameUUIDFromBytes((snapshot.versionId() + ":"
                    + snapshot.inputHash() + ":" + firstRef).getBytes(StandardCharsets.UTF_8)) : old.id();
            surviving.add(id);
            for (var member : component.members()) {
                assignments.put(member.articleRef(), id);
                evidence.put(member.articleRef(), member);
                medoids.put(member.articleRef(), component.medoidArticleRef());
            }
            if (candidates.size() > 1) count(counts, "merge");
            if (component.members().size() == 1) count(counts, "singleton");
            Instant from = component.members().stream().map(m -> inputs.get(m.articleRef()).effectiveAt())
                    .min(Instant::compareTo).orElseThrow();
            Instant to = component.members().stream().map(m -> inputs.get(m.articleRef()).effectiveAt())
                    .max(Instant::compareTo).orElseThrow();
            boolean changed = old == null || component.members().stream().anyMatch(m -> {
                Membership before = previous.get(m.articleRef());
                return before == null || !before.storyId().equals(id)
                        || !before.fingerprint().equals(m.articleInputFingerprint());
            }) || plan.basis().memberships().stream().anyMatch(m -> m.storyId().equals(id)
                    && !Objects.equals(componentByArticle.get(m.ref()), componentByArticle.get(firstRef)))
                    || !old.representative().equals(component.medoidArticleRef())
                    || !old.from().equals(from) || !old.to().equals(to);
            String state = changed ? "ACTIVE" : old.state();
            if (!changed && "ACTIVE".equals(state)) {
                Instant lastEvent = component.members().stream().map(m -> inputs.get(m.articleRef()).createdAt())
                        .max(Instant::compareTo).orElse(old.updatedAt());
                if (old.updatedAt().isAfter(lastEvent)) lastEvent = old.updatedAt();
                if (!snapshot.watermark().isBefore(lastEvent.plus(Duration.ofHours(72)))) state = "CLOSED";
            }
            if (old == null) {
                jdbc.update("""
                        INSERT INTO stories (id, public_id, clustering_version_id, identity_anchor_article_ref,
                            representative_article_ref, state, effective_from, effective_to,
                            created_by_run_id, last_changed_by_run_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                        """, id, id, snapshot.versionId(), firstRef, component.medoidArticleRef(),
                        ts(from), ts(to), run.id(), run.id(), ts(now), ts(now));
                count(counts, "new");
            } else if (changed || !state.equals(old.state())) {
                updateStory(old, component.medoidArticleRef(), state, from, to, run, now);
                if (!state.equals(old.state())) count(counts, "CLOSED".equals(state) ? "closed" : "reopened");
                if (changed && component.members().size() > plan.basis().memberships().stream()
                        .filter(m -> m.storyId().equals(id)).count()) count(counts, "expanded");
            }
        }
        for (Story old : plan.basis().stories()) {
            if (!surviving.contains(old.id())) {
                updateStory(old, old.representative(), "SUPERSEDED", old.from(), old.to(), run, now);
                count(counts, "superseded");
            }
        }

        int changed = 0;
        for (Input input : plan.inputs()) {
            UUID target = assignments.get(input.ref());
            Membership old = previous.get(input.ref());
            // A corrected anchor can move the identity of a whole source component.
            if (!plan.processedArticleRefs().contains(input.ref())
                    && (old == null || target == null || old.storyId().equals(target))) continue;
            boolean same = old != null && old.storyId().equals(target)
                    && old.fingerprint().equals(input.fingerprint());
            String reason = target != null ? "MEDOID_RADIUS"
                    : input.effectiveAt().isAfter(snapshot.watermark()) ? "AFTER_WATERMARK"
                    : "USABLE".equals(input.usability()) ? "EMBEDDING_NOT_READY" : input.usability();
            if (target == null) count(counts, "unassigned." + reason);
            String result = target == null ? "UNASSIGNED" : same ? "NO_CHANGE" : "ASSIGNED";
            var memberEvidence = evidence.get(input.ref());
            long decision = jdbc.queryForObject("""
                    INSERT INTO story_assignment_decisions (decision_hash, clustering_version_id,
                        run_id, snapshot_id, article_input_id, article_ref, article_input_fingerprint,
                        previous_story_id, resulting_story_id, result, assignment_reason,
                        component_rule_version, best_comparison_article_ref, best_cosine_similarity,
                        created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, hash(snapshot.inputHash() + ":" + input.ref()), snapshot.versionId(),
                    run.id(), snapshot.id(), input.id(), input.ref(), input.fingerprint(),
                    old == null ? null : old.storyId(), target, result, reason,
                    plan.partition().snapshot().componentRuleVersion(), medoids.get(input.ref()),
                    memberEvidence == null ? null : memberEvidence.similarityToMedoid(), ts(now));
            if (!same && old != null) {
                jdbc.update("""
                        UPDATE story_memberships SET current_marker = NULL, valid_to_run_id = ?, ended_at = ?
                        WHERE id = ? AND current_marker = 1
                        """, run.id(), ts(now), old.id());
            }
            if (!same && target != null) {
                jdbc.update("""
                        INSERT INTO story_memberships (story_id, clustering_version_id, article_id,
                            article_ref, article_input_fingerprint, valid_from_run_id, decision_id,
                            assignment_reason, current_marker, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                        """, target, snapshot.versionId(), input.articleId(), input.ref(), input.fingerprint(),
                        run.id(), decision, reason, ts(now));
            }
            if (!same && (target != null || old != null)) changed++;
        }
        for (Membership old : plan.basis().memberships()) {
            if (!inputs.containsKey(old.ref())) {
                throw conflict("Snapshot omits a current member; create a fresh snapshot");
            }
        }
        if (plan.incremental()) {
            closeUnchangedStories(snapshot, run, now, scope, counts);
            Set<UUID> projections = projectionChanges(snapshot);
            projections.addAll(surviving);
            refreshListValues(snapshot.versionId(), projections.size(), projections);
            projections.addAll(scope); // Also clear locations of superseded source stories.
            locationMaterializer.refresh(snapshot.versionId(), projections);
        } else {
            refreshListValues(snapshot.versionId(), surviving.size(), null);
            locationMaterializer.refresh(snapshot.versionId());
        }
        jdbc.update("""
                INSERT INTO story_publish_commits (publish_key, clustering_version_id, snapshot_id,
                    run_id, fencing_token, published_at) VALUES (?, ?, ?, ?, ?, ?)
                """, hash(snapshot.versionId() + ":" + snapshot.inputHash()), snapshot.versionId(),
                snapshot.id(), run.id(), run.fencingToken(), ts(now));
        counts.put("memberships", (long) assignments.size());
        return new Result("PUBLISHED", changed,
                Math.max(0, plan.processedArticleRefs().size() - changed), counts);
    }

    void assertLease(long versionId, StorySnapshotRepository.RunClaim run, Instant now, Duration lease) {
        boolean valid = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM story_processing_runs run
                    WHERE run.id = ? AND run.clustering_version_id = ?
                      AND run.status = 'RUNNING' AND run.fencing_token = ? AND run.started_at > ?
                      AND run.fencing_token = (SELECT MAX(fencing_token) FROM story_processing_runs
                          WHERE clustering_version_id = ?))
                """, Boolean.class, run.id(), versionId, run.fencingToken(), ts(now.minus(lease)), versionId));
        if (!valid) throw conflict("Publisher lease expired or fencing token superseded");
    }

    void record(Result result) {
        metrics.counter("stories.publish.runs", "result", result.outcome()).increment();
        result.counts().forEach((name, count) ->
                metrics.counter("stories.publish.results", "kind", name).increment(count));
    }

    private IllegalStateException conflict(String message) {
        metrics.counter("stories.publish.conflicts").increment();
        return new IllegalStateException(message);
    }

    private void refreshListValues(long versionId, int expectedStories, Set<UUID> scope) {
        if (scope != null && scope.isEmpty()) return;
        String memberScope = scope == null ? "" : " AND member.story_id IN (:scope)\n";
        String storyScope = scope == null ? "" : " AND candidate.id IN (:scope)\n";
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("version", versionId);
        if (scope != null) parameters.put("scope", scope);
        int refreshed = new NamedParameterJdbcTemplate(jdbc).update("""
                UPDATE stories story
                SET representative_article_id = projection.article_id,
                    representative_canonical_url = projection.canonical_url,
                    representative_domain = projection.domain,
                    representative_first_seen_at = projection.first_seen_at,
                    representative_title = projection.title,
                    representative_published_at = projection.published_at,
                    representative_main_image_url = projection.main_image_url,
                    member_count = projection.member_count,
                    source_count = projection.source_count,
                    independent_report_count = projection.independent_report_count
                FROM (
                    SELECT candidate.id AS story_id, article.id AS article_id,
                           article.canonical_url, article.domain, article.first_seen_at,
                           (SELECT g.page_title FROM gdelt_gkg g
                            WHERE g.article_id = article.id AND g.page_title IS NOT NULL
                              AND TRIM(g.page_title) <> ''
                            ORDER BY g.source_timestamp, g.id LIMIT 1) AS title,
                           (SELECT g.page_precise_pub_timestamp FROM gdelt_gkg g
                            WHERE g.article_id = article.id
                              AND g.page_precise_pub_timestamp IS NOT NULL
                            ORDER BY g.source_timestamp, g.id LIMIT 1) AS published_at,
                           (SELECT g.main_image_url FROM gdelt_gkg g
                            WHERE g.article_id = article.id AND g.main_image_url IS NOT NULL
                            ORDER BY g.source_timestamp, g.id LIMIT 1) AS main_image_url,
                           members.member_count, members.source_count,
                           members.independent_report_count
                    FROM stories candidate
                    JOIN articles article ON article.url_hash = candidate.representative_article_ref
                    JOIN (
                        SELECT member.story_id, COUNT(*)::INTEGER AS member_count,
                               COUNT(DISTINCT source.domain)::INTEGER AS source_count,
                               COUNT(DISTINCT input.title_input_hash)::INTEGER
                                   AS independent_report_count
                        FROM story_memberships member
                        JOIN articles source ON source.id = member.article_id
                        JOIN story_article_inputs input
                          ON input.clustering_version_id = member.clustering_version_id
                         AND input.article_ref = member.article_ref
                         AND input.article_input_fingerprint = member.article_input_fingerprint
                        WHERE member.clustering_version_id = :version AND member.current_marker = 1
                        """ + memberScope + """
                        GROUP BY member.story_id
                    ) members ON members.story_id = candidate.id
                    WHERE candidate.clustering_version_id = :version AND candidate.state <> 'SUPERSEDED'
                    """ + storyScope + """
                ) projection
                WHERE story.id = projection.story_id
                """, parameters);
        if (refreshed != expectedStories) {
            throw conflict("Story list projection is incomplete");
        }
    }

    private void updateStory(Story old, String representative, String state, Instant from, Instant to,
                             StorySnapshotRepository.RunClaim run, Instant now) {
        int updated = jdbc.update("""
                UPDATE stories SET representative_article_ref = ?, state = ?, effective_from = ?,
                    effective_to = ?, last_changed_by_run_id = ?, updated_at = ?,
                    closed_at = ?, superseded_at = ?, optimistic_version = optimistic_version + 1
                WHERE id = ? AND optimistic_version = ?
                """, representative, state, ts(from), ts(to), run.id(), ts(now),
                "CLOSED".equals(state) ? ts(now) : null,
                "SUPERSEDED".equals(state) ? ts(now) : null, old.id(), old.version());
        if (updated != 1) throw conflict("Story optimistic version changed");
    }

    List<Story> stories(long versionId) { return stories(versionId, null); }

    private List<Story> stories(long versionId, Set<UUID> scope) {
        if (scope != null && scope.isEmpty()) return List.of();
        return new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT * FROM stories WHERE clustering_version_id = :version AND state <> 'SUPERSEDED'
                """ + (scope == null ? "" : " AND id IN (:scope)") + " ORDER BY created_at, id FOR UPDATE",
                scope == null ? Map.of("version", versionId) : Map.of("version", versionId, "scope", scope), (rs, row) -> new Story(rs.getObject("id", UUID.class), rs.getObject("public_id", UUID.class),
                rs.getString("identity_anchor_article_ref"), rs.getString("representative_article_ref"),
                rs.getString("state"), rs.getTimestamp("effective_from").toInstant(),
                rs.getTimestamp("effective_to").toInstant(), rs.getLong("optimistic_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()));
    }

    List<Membership> memberships(long versionId) { return memberships(versionId, null); }

    private List<Membership> memberships(long versionId, Set<UUID> scope) {
        if (scope != null && scope.isEmpty()) return List.of();
        return new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT id, story_id, article_ref, article_input_fingerprint FROM story_memberships
                WHERE clustering_version_id = :version AND current_marker = 1
                """ + (scope == null ? "" : " AND story_id IN (:scope)") + " ORDER BY article_ref",
                scope == null ? Map.of("version", versionId) : Map.of("version", versionId, "scope", scope), (rs, row) -> new Membership(rs.getLong("id"), rs.getObject("story_id", UUID.class),
                rs.getString("article_ref"), rs.getString("article_input_fingerprint")));
    }

    private Set<UUID> storyIds(long versionId, Set<String> refs) {
        if (refs.isEmpty()) return Set.of();
        return new HashSet<>(new NamedParameterJdbcTemplate(jdbc).queryForList("""
                SELECT DISTINCT story_id FROM story_memberships
                WHERE clustering_version_id = :version AND current_marker = 1 AND article_ref IN (:refs)
                """, Map.of("version", versionId, "refs", refs), UUID.class));
    }

    private void closeUnchangedStories(StorySnapshotRepository.Snapshot snapshot,
                                       StorySnapshotRepository.RunClaim run, Instant now,
                                       Set<UUID> scope, Map<String, Long> counts) {
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("version", snapshot.versionId());
        parameters.put("cutoff", ts(snapshot.watermark().minus(Duration.ofHours(72))));
        parameters.put("run", run.id());
        parameters.put("now", ts(now));
        parameters.put("scope", scope.isEmpty() ? Set.of(new UUID(0, 0)) : scope);
        int closed = new NamedParameterJdbcTemplate(jdbc).update("""
                UPDATE stories story SET state = 'CLOSED', closed_at = :now, updated_at = :now,
                    last_changed_by_run_id = :run, optimistic_version = optimistic_version + 1
                WHERE story.clustering_version_id = :version AND story.state = 'ACTIVE'
                  AND story.id NOT IN (:scope) AND story.updated_at <= :cutoff
                  AND NOT EXISTS (
                    SELECT 1 FROM story_memberships member
                    JOIN story_article_inputs input ON input.clustering_version_id = member.clustering_version_id
                      AND input.article_ref = member.article_ref
                      AND input.article_input_fingerprint = member.article_input_fingerprint
                    WHERE member.story_id = story.id AND member.current_marker = 1
                      AND input.created_at > :cutoff)
                """, parameters);
        if (closed > 0) counts.merge("closed", (long) closed, Long::sum);
    }

    /** Source-only changes (including late location/confidence evidence) do not change embeddings. */
    private Set<UUID> projectionChanges(StorySnapshotRepository.Snapshot snapshot) {
        return new HashSet<>(jdbc.queryForList("""
                WITH ready AS (
                    SELECT source_key FROM story_projection_dirty_sources
                    WHERE EXISTS (SELECT 1 FROM story_clustering_versions
                        WHERE id = ? AND status = 'ACTIVE')
                    ORDER BY source_key FOR UPDATE SKIP LOCKED
                ), consumed AS (
                    DELETE FROM story_projection_dirty_sources dirty USING ready
                    WHERE dirty.source_key = ready.source_key
                    RETURNING dirty.article_id, dirty.global_event_id, dirty.mention_identifier
                ), changed_articles AS (
                    SELECT article_id FROM consumed WHERE article_id IS NOT NULL
                    UNION
                    SELECT event.article_id FROM consumed source
                    JOIN gdelt_events event ON event.global_event_id = source.global_event_id
                      AND event.source_url = source.mention_identifier
                    WHERE event.article_id IS NOT NULL
                )
                SELECT DISTINCT member.story_id FROM changed_articles source
                JOIN story_memberships member ON member.article_id = source.article_id
                JOIN stories story ON story.id = member.story_id
                WHERE member.clustering_version_id = ? AND member.current_marker = 1
                  AND story.state <> 'SUPERSEDED'
                """, UUID.class, snapshot.versionId(), snapshot.versionId()));
    }

    long latestCommit(long versionId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM story_publish_commits WHERE clustering_version_id = ?",
                Long.class, versionId);
    }

    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }
    private static String hash(String value) {
        return StorySnapshotCanonicalizer.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    private static void count(Map<String, Long> counts, String key) { counts.merge(key, 1L, Long::sum); }

    record Story(UUID id, UUID publicId, String anchor, String representative, String state, Instant from, Instant to,
                 long version, Instant createdAt, Instant updatedAt) { }
    record Membership(long id, UUID storyId, String ref, String fingerprint) { }
    record Input(long id, long articleId, String ref, String fingerprint, Instant effectiveAt,
                 Instant createdAt, String usability) { }
    record Basis(List<Story> stories, List<Membership> memberships, long commitId) { }
    record Plan(StorySnapshotRepository.Snapshot snapshot, Basis basis, List<Input> inputs,
                StoryPartitionService.Partition partition, Set<String> processedArticleRefs, boolean incremental) { }
    record Result(String outcome, int changed, int skipped, Map<String, Long> counts) { }
}
