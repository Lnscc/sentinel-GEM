# GEM-103 release decision

- Candidate: `story-mvp-title-embedding-24h-v1.2.0` (ID `5`)
- Review date: 2026-11-14
- Reviewer: Linus
- Holdout: `gem103-holdout-v1.0.0`
- Gate: `story-release-gates-v2`
- Result: approved and promoted

## Evaluation

The independent holdout contains 20 positive and 20 negative story pairs plus nine
input-quality cases. The candidate produced 20 true positives, no false positives or false
negatives, and no input-quality deviations. Precision and recall are both `1.00`.

Holdout hash: `79109c301c3d6391a8e8d0bd2f5ddd49d0c1be0179179aaaa126401daa5204b0`

## Regression evidence

The candidate introduces no deviation beyond the active version. The five known ART-045
relationship deviations remain visible; all seven ART-045 input-quality cases pass. Missing
operational rows are evaluated from the frozen corpus so retention cannot create false failures.

## Story diff

At watermark `2026-11-14T18:16:49.155119Z`, the previous active version contains 1,012 stories
and 1,275 memberships; candidate 1.2 contains 993 stories and 1,263 memberships. 83 memberships
change identity, with 18 merges and four splits.

Review basis hash: `c2446e654e466959548855a0b92c0f3ed189d1755aeaf4604d70300e82c8e9d1`

## Decision

The unchanged candidate passes the fixed holdout gates and adds no regression relative to the
active version. Linus approved atomic promotion on 2026-11-14. Version 1.2 is now the only
`ACTIVE` version. Verification found 1,012 visible stories and successfully loaded story detail
`35ed7ca9-3bb2-3601-858d-53d95865c0e4`. All five confirmed advice pages have no membership; all
four same-domain event reports remain visible. Reproducible evidence is stored in
`docs/analysis/story-mvp-title-embedding-24h-v1.2.0-release-evidence.json`.
