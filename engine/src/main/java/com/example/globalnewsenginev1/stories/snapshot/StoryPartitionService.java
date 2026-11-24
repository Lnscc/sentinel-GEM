package com.example.globalnewsenginev1.stories.snapshot;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

@Service
public class StoryPartitionService {

    static final String COMPONENT_RULE = "medoid-radius-agglomerative-v1";
    static final String COHERENT_COMPONENT_RULE = "complete-linkage-template-coherent-v2";
    private final StorySnapshotRepository repository;

    StoryPartitionService(StorySnapshotRepository repository) {
        this.repository = repository;
    }

    /** Reconstructs a partition from immutable snapshot artifacts without publishing it. */
    public Partition calculate(long snapshotId) {
        SnapshotRules rules = repository.loadPartitionRules(snapshotId);
        var inputs = repository.loadSnapshotInputs(snapshotId);
        if (repository.isCompacted(snapshotId)) {
            throw new IllegalStateException("Snapshot is archived: " + snapshotId);
        }
        return partition(rules, inputs);
    }

    static Partition partitionForPublication(SnapshotRules rules,
            List<StorySnapshotRepository.SnapshotInput> inputs,
            List<StorySnapshotService.PairDecision> pairs) {
        if (inputs.isEmpty()) return partition(rules, inputs, false);
        Map<String, Integer> indexes = new HashMap<>();
        int[] parents = new int[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            indexes.put(inputs.get(i).articleRef(), i);
            parents[i] = i;
        }
        // Every possible medoid merge is an edge in the already computed exact candidate graph.
        // Disconnected groups cannot influence each other, even after medoids change.
        for (var pair : pairs) {
            if ("SAME_STORY".equals(pair.result())) {
                int left = root(parents, indexes.get(pair.left().articleRef()));
                int right = root(parents, indexes.get(pair.right().articleRef()));
                parents[Math.max(left, right)] = Math.min(left, right);
            }
        }
        Map<Integer, List<StorySnapshotRepository.SnapshotInput>> groups = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            groups.computeIfAbsent(root(parents, i), ignored -> new ArrayList<>()).add(inputs.get(i));
        }
        List<Component> components = new ArrayList<>();
        for (var group : groups.values()) {
            components.addAll(partition(rules, group, false).components());
        }
        components.sort(Comparator
                .comparing((Component c) -> inputs.get(indexes.get(c.members().getFirst().articleRef())).effectiveAt())
                .thenComparing(c -> c.members().getFirst().articleRef()));
        return new Partition(rules, List.copyOf(components), List.of());
    }

    static Partition incrementalPartition(
            SnapshotRules rules,
            List<StorySnapshotRepository.SnapshotInput> inputs,
            StoryIncrementalPairSearch search,
            Set<String> changedArticleRefs,
            List<StorySnapshotRepository.ExistingAssignment> existingAssignments
    ) {
        validate(rules);
        Map<String, StorySnapshotRepository.SnapshotInput> inputByRef = new HashMap<>();
        inputs.forEach(input -> inputByRef.put(input.articleRef(), input));
        Map<String, Object> groupByRef = new HashMap<>();
        Map<UUID, String> representativeByStory = new HashMap<>();
        Set<Object> affectedGroups = new HashSet<>();
        for (var assignment : existingAssignments) {
            representativeByStory.put(assignment.storyId(), assignment.representativeArticleRef());
            if (changedArticleRefs.contains(assignment.articleRef())) affectedGroups.add(assignment.storyId());
            if (!changedArticleRefs.contains(assignment.articleRef())
                    && inputByRef.containsKey(assignment.articleRef())) {
                groupByRef.put(assignment.articleRef(), assignment.storyId());
            }
        }
        Map<Object, List<StorySnapshotRepository.SnapshotInput>> membersByGroup = new HashMap<>();
        for (var input : inputs) {
            Object group = groupByRef.get(input.articleRef());
            if (group != null) {
                membersByGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(input);
            }
        }
        List<StorySnapshotRepository.SnapshotInput> changedInputs = inputs.stream()
                .filter(input -> changedArticleRefs.contains(input.articleRef()))
                .sorted(Comparator.comparing(StorySnapshotRepository.SnapshotInput::effectiveAt)
                        .thenComparing(StorySnapshotRepository.SnapshotInput::articleRef))
                .toList();
        for (var input : changedInputs) {
            var match = search.findMatch(input, candidate -> {
                Object group = groupByRef.get(candidate.articleRef());
                return group != null && coherent(rules, input, membersByGroup.get(group), search);
            });
            Object group = match == null ? input.articleRef() : groupByRef.get(match.articleRef());
            affectedGroups.add(group);
            groupByRef.put(input.articleRef(), group);
            membersByGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(input);
        }

        Map<Object, List<StorySnapshotRepository.SnapshotInput>> groups = new LinkedHashMap<>();
        for (var input : inputs) {
            Object group = groupByRef.computeIfAbsent(input.articleRef(), ignored -> input.articleRef());
            if (affectedGroups.contains(group)) {
                groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(input);
            }
        }
        List<Component> components = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<StorySnapshotRepository.SnapshotInput> members = entry.getValue();
            String representativeRef = entry.getKey() instanceof UUID storyId
                    ? representativeByStory.get(storyId) : null;
            StorySnapshotRepository.SnapshotInput representative = inputByRef.get(representativeRef);
            if (representative == null || !members.contains(representative)) {
                representative = members.getFirst();
            }
            float[] representativeVector = search.vector(representative);
            List<MemberEvidence> evidence = new ArrayList<>();
            for (var member : members) {
                BigDecimal similarity = member == representative ? BigDecimal.ONE.setScale(6)
                        : ExactCosine.similarity(representativeVector, search.vector(member));
                evidence.add(new MemberEvidence(member.articleRef(), member.articleInputFingerprint(),
                        member.embeddingArtifactId(), member.vectorHash(), similarity,
                        Duration.between(member.effectiveAt(), representative.effectiveAt()).abs()));
            }
            components.add(new Component(representative.articleRef(), List.copyOf(evidence)));
        }
        return new Partition(rules, List.copyOf(components), List.of());
    }

    private static int root(int[] parents, int index) {
        while (parents[index] != index) {
            parents[index] = parents[parents[index]];
            index = parents[index];
        }
        return index;
    }

    static Partition partition(SnapshotRules rules,
                               List<StorySnapshotRepository.SnapshotInput> frozenInputs) {
        return partition(rules, frozenInputs, true);
    }

    static Partition partition(SnapshotRules rules,
                               List<StorySnapshotRepository.SnapshotInput> frozenInputs,
                               boolean includeMergeDiagnostics) {
        validate(rules);
        List<StorySnapshotRepository.SnapshotInput> inputs = frozenInputs.stream()
                .sorted(Comparator.comparing(StorySnapshotRepository.SnapshotInput::effectiveAt)
                        .thenComparing(StorySnapshotRepository.SnapshotInput::articleRef)).toList();
        Set<String> refs = new HashSet<>();
        List<float[]> vectors = new ArrayList<>();
        List<Cluster> clusters = new ArrayList<>();
        for (var input : inputs) {
            if (!refs.add(input.articleRef())) {
                throw new IllegalArgumentException("Duplicate snapshot article: " + input.articleRef());
            }
            if (input.embeddingDimension() != rules.dimension()) {
                throw new ExactCosine.InvalidVectorException("DIMENSION_MISMATCH",
                        "Artifact dimension differs from clustering version");
            }
            vectors.add(ExactCosine.decode(input.vectorBytes(), rules.dimension(), input.vectorHash()));
            int index = clusters.size();
            clusters.add(new Cluster(List.of(index), index));
        }
        Scores scores = new Scores(vectors);
        Duration window = Duration.ofHours(rules.windowHours());
        long threshold = rules.threshold().movePointRight(6).longValueExact();
        List<MergeDecision> decisions = new ArrayList<>();
        Comparator<Candidate> order = Comparator.comparingLong(Candidate::similarity).reversed()
                .thenComparing(c -> inputs.get(c.left().medoid()).articleRef())
                .thenComparing(c -> inputs.get(c.right().medoid()).articleRef());
        PriorityQueue<Candidate> candidates = new PriorityQueue<>(order);
        Set<Cluster> active = new HashSet<>(clusters);
        for (int i = 0; i < clusters.size(); i++) {
            for (int j = i + 1; j < clusters.size(); j++) {
                addCandidate(candidates, clusters.get(i), clusters.get(j), inputs, scores, window, threshold);
            }
        }
        while (!candidates.isEmpty()) {
            Candidate candidate = candidates.remove();
            if (!active.contains(candidate.left()) || !active.contains(candidate.right())) continue;
            List<Integer> members = new ArrayList<>(candidate.left().members());
            members.addAll(candidate.right().members());
            members.sort(Integer::compareTo);
            int medoid = medoid(members, scores);
            List<MemberEvidence> evidence = evidence(members, medoid, inputs, scores);
            boolean accepted = evidence.stream().allMatch(member ->
                    member.similarityToMedoid().compareTo(rules.threshold()) >= 0
                            && member.timeDistance().compareTo(window) <= 0)
                    && (!COHERENT_COMPONENT_RULE.equals(rules.componentRuleVersion())
                    || coherent(members, inputs, scores, window, threshold));
            if (includeMergeDiagnostics) decisions.add(new MergeDecision(refs(candidate.left(), inputs),
                    refs(candidate.right(), inputs), decimal(candidate.similarity()),
                    inputs.get(medoid).articleRef(), accepted, evidence));
            if (accepted) {
                active.remove(candidate.left());
                active.remove(candidate.right());
                clusters.remove(candidate.left());
                clusters.remove(candidate.right());
                Cluster merged = new Cluster(List.copyOf(members), medoid);
                for (Cluster cluster : clusters) {
                    addCandidate(candidates, merged, cluster, inputs, scores, window, threshold);
                }
                clusters.add(merged);
                active.add(merged);
                clusters.sort(Comparator.comparingInt(c -> c.members().getFirst()));
            }
        }
        List<Component> components = clusters.stream().map(cluster -> new Component(
                inputs.get(cluster.medoid()).articleRef(),
                evidence(cluster.members(), cluster.medoid(), inputs, scores))).toList();
        return new Partition(rules, components, List.copyOf(decisions));
    }

    private static void validate(SnapshotRules rules) {
        if (!Set.of(COMPONENT_RULE, COHERENT_COMPONENT_RULE).contains(rules.componentRuleVersion())
                || !"exact-cosine-radius-v1".equals(rules.searchMode())
                || !Set.of(24, 48, 72).contains(rules.windowHours())
                || rules.threshold().compareTo(new BigDecimal("0.700000")) != 0
                || rules.dimension() <= 0) {
            throw new IllegalArgumentException("Unsupported clustering version: " + rules.versionKey());
        }
    }

    private static void addCandidate(PriorityQueue<Candidate> candidates, Cluster first, Cluster second,
                                     List<StorySnapshotRepository.SnapshotInput> inputs, Scores scores,
                                     Duration window, long threshold) {
        Cluster left = first;
        Cluster right = second;
        if (inputs.get(left.medoid()).articleRef().compareTo(inputs.get(right.medoid()).articleRef()) > 0) {
            left = second;
            right = first;
        }
        if (distance(inputs, left.medoid(), right.medoid()).compareTo(window) <= 0) {
            long score = scores.get(left.medoid(), right.medoid());
            if (score >= threshold) candidates.add(new Candidate(left, right, score));
        }
    }

    private static int medoid(List<Integer> members, Scores scores) {
        int best = members.getFirst();
        long bestSum = Long.MIN_VALUE;
        for (int member : members) {
            long sum = 0;
            for (int other : members) {
                if (member != other) {
                    sum += scores.get(member, other);
                }
            }
            // The denominator is identical for every member; sorted members resolve ties.
            if (sum > bestSum) {
                best = member;
                bestSum = sum;
            }
        }
        return best;
    }

    private static boolean coherent(SnapshotRules rules,
                                    StorySnapshotRepository.SnapshotInput input,
                                    List<StorySnapshotRepository.SnapshotInput> members,
                                    StoryIncrementalPairSearch search) {
        if (!COHERENT_COMPONENT_RULE.equals(rules.componentRuleVersion())) return true;
        Duration window = Duration.ofHours(rules.windowHours());
        float[] inputVector = search.vector(input);
        for (var member : members) {
            if (ExactCosine.similarity(inputVector, search.vector(member))
                    .compareTo(rules.threshold()) < 0
                    || Duration.between(input.effectiveAt(), member.effectiveAt()).abs().compareTo(window) > 0
                    || !StorySubjectCoherence.compatible(input.normalizedTitle(), member.normalizedTitle())) {
                return false;
            }
        }
        return true;
    }

    private static boolean coherent(List<Integer> members,
                                    List<StorySnapshotRepository.SnapshotInput> inputs,
                                    Scores scores, Duration window, long threshold) {
        for (int left = 0; left < members.size(); left++) {
            for (int right = left + 1; right < members.size(); right++) {
                int leftIndex = members.get(left);
                int rightIndex = members.get(right);
                if (scores.get(leftIndex, rightIndex) < threshold
                        || distance(inputs, leftIndex, rightIndex).compareTo(window) > 0
                        || !StorySubjectCoherence.compatible(inputs.get(leftIndex).normalizedTitle(),
                        inputs.get(rightIndex).normalizedTitle())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<String> refs(Cluster cluster,
                                    List<StorySnapshotRepository.SnapshotInput> inputs) {
        return cluster.members().stream().map(index -> inputs.get(index).articleRef()).toList();
    }

    private static List<MemberEvidence> evidence(List<Integer> members, int medoid,
                                               List<StorySnapshotRepository.SnapshotInput> inputs,
                                               Scores scores) {
        return members.stream().map(index -> {
            var input = inputs.get(index);
            return new MemberEvidence(input.articleRef(), input.articleInputFingerprint(),
                    input.embeddingArtifactId(), input.vectorHash(),
                    decimal(scores.get(index, medoid)), distance(inputs, index, medoid));
        }).toList();
    }

    private static Duration distance(List<StorySnapshotRepository.SnapshotInput> inputs,
                                     int left, int right) {
        return Duration.between(inputs.get(left).effectiveAt(), inputs.get(right).effectiveAt()).abs();
    }

    private static BigDecimal decimal(long score) {
        return BigDecimal.valueOf(score, 6);
    }

    private record Cluster(List<Integer> members, int medoid) { }
    private record Candidate(Cluster left, Cluster right, long similarity) { }

    private static final class Scores {
        private final List<float[]> vectors;
        // Eviction only causes exact recomputation; it never changes scores or the partition.
        private final Map<Long, Long> cache = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                return size() > 65_536;
            }
        };

        Scores(List<float[]> vectors) {
            this.vectors = vectors;
        }

        long get(int left, int right) {
            if (left == right) {
                return 1_000_000;
            }
            int first = Math.min(left, right);
            int second = Math.max(left, right);
            long key = ((long) first << 32) | second;
            return cache.computeIfAbsent(key, ignored -> ExactCosine
                    .similarity(vectors.get(first), vectors.get(second))
                    .movePointRight(6).longValueExact());
        }
    }

    public record SnapshotRules(long snapshotId, String snapshotKey, String snapshotInputHash,
                                String versionKey, int dimension, int windowHours,
                                BigDecimal threshold, String searchMode,
                                String componentRuleVersion) { }

    public record MemberEvidence(String articleRef, String articleInputFingerprint,
                                 long embeddingArtifactId, String vectorHash,
                                 BigDecimal similarityToMedoid, Duration timeDistance) { }

    public record Component(String medoidArticleRef, List<MemberEvidence> members) { }

    public record MergeDecision(List<String> leftArticleRefs, List<String> rightArticleRefs,
                                BigDecimal medoidSimilarity, String proposedMedoidArticleRef,
                                boolean accepted, List<MemberEvidence> members) { }

    public record Partition(SnapshotRules snapshot, List<Component> components,
                            List<MergeDecision> decisions) { }
}
