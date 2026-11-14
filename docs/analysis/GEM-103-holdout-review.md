# GEM-103: Holdout review

Status: labelled and reviewed on 2026-11-14

## Basis

- Candidate: `story-mvp-title-embedding-24h-v1.2.0`
- Source: locally imported GDELT articles first seen at `2026-11-14T17:15:00Z`
- Snapshot watermark: `2026-11-14T18:16:49.155119Z`
- Labels: 20 positive and 20 negative story pairs plus five exclusions and four
  same-domain include counterexamples in `docs/analysis/GEM-103-holdout-v1.json`
- Label responsibility: Codex on behalf of Linus

## Independence

The candidate rule and `story-release-gates-v2` were frozen before this holdout was created.
Every article reference is disjoint from ART-032, ART-044, ART-045, and ART-048 evidence. The
holdout was not used to change `non-event-content-v1`, clustering behavior, thresholds, features,
or release gates.

ART-045 remains the separate known-regression corpus and is not counted toward holdout gates.
Changing the rule after this review requires a new independent holdout.

## Review method

Positive pair labels require the same concrete report; negative pairs cover clearly unrelated
events or publication subjects. Input-quality exclusions are narrowly identified advice pages.
Include counterexamples are normal reports from the same domains and guard against domain-wide
or broad title-based filtering. Ambiguous cases were omitted. The machine-readable file is the
canonical evaluation input.
