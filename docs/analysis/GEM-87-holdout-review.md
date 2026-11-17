# GEM-87: Independent holdout review

Status: original ART-048 review blocked by missing data; the fresh independent
holdout passed on 2026-11-17. Version 1.3 was subsequently activated after
separate explicit authorization.

## Basis

- Candidate: `story-mvp-title-embedding-24h-v1.3.0`
- Component rule: `complete-linkage-template-coherent-v2`
- Regression corpus: `gem87-clustering-quality-v1.2.0`
- Independent holdout: `art048-holdout-v1.0.0`
- Holdout source window: `firstSeenAt` from 2026-09-03T08:15:00Z through
  2026-09-03T14:30:00Z
- Labels: 20 positive and 20 negative pairs with rationales in
  `docs/analysis/ART-048-holdout-v1.json`

## Independence

ART-048 was labelled and reviewed on 2026-10-07, before GEM-87 and before the
v2 component rule existed. It was not inspected or changed while selecting the
complete-linkage or subject-coherence behavior. The rule and the versioned
GEM-87 regression cases were frozen before running this holdout. ART-048 is
disjoint from the GEM-87 regression fixture and remains unchanged.

## Review method

The existing release workflow will evaluate the immutable candidate snapshot
against the original ART-048 labels, the default ART-045 relationship corpus,
and the mandatory GEM-87 component regression corpus. A pass requires the fixed
release gates, no new relationship regression relative to the active version,
and zero GEM-87 component deviations. This review does not authorize promotion;
version 1.3 remains `SHADOW` after evaluation.

The local review attempts on 2026-11-15 did not complete because the live
active pipeline advanced while each candidate snapshot was being built. The
freshness gate rejected both attempts, so no release evidence was produced.
The repeat on 2026-11-17 used a controlled window and completed the candidate
snapshot successfully. Holdout evaluation then stopped: all 40 ART-048 article
references are absent from the current database. The ten ART-045 relationship
references are also absent. No holdout quality result is claimed. Restore the
original evaluation inputs or prepare a new independent holdout before repeating
the full review; details and partial regression results are in
`GEM-87-release-decision.md` and `GEM-87-review-20261117.json`.

At Linus's request a new disjoint holdout was subsequently labelled and sealed
before its predictions were read. The full workflow completed on a stable
watermark: precision 1.00, recall 0.95, zero input-quality deviations; fixed gates
passed. See `GEM-87-fresh-holdout-review.md` and
`GEM-87-fresh-holdout-release-evidence.json`. Historical ART-045 relationship
coverage is still unavailable. This non-promoting review did not activate the
candidate. The later authorized activation and live verification are recorded
in `GEM-87-release-decision.md` and `GEM-87-v1.3.0-activation-evidence.json`.
