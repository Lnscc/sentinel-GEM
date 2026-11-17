# GEM-87: Release decision

Decision: approved and activated `story-mvp-title-embedding-24h-v1.3.0`
on 2026-11-17 after Linus's explicit activation authorization. Version 1.2 is
`RETIRED`; its historical data is retained.

The candidate is installed as `SHADOW` with component rule
`complete-linkage-template-coherent-v2`. Focused regression, migration, release
workflow, and PostgreSQL snapshot tests pass. The release command now always
combines the ART-045 relationship corpus with the versioned GEM-87 component
corpus; any GEM-87 component deviation blocks release absolutely.

Two local non-promoting review attempts were made on 2026-11-15 using the
independent `art048-holdout-v1.0.0` corpus. Both candidate snapshots completed,
but the live v1.2 pipeline published a newer active watermark during snapshot
construction. The release workflow correctly rejected the first attempt after
a concurrent 759-article import and rejected the second because the candidate
was older than the active version.

## Controlled review on 2026-11-17

The non-promoting workflow was repeated with background jobs temporarily disabled.
The active v1.2 watermark stayed fixed at `2026-11-17T20:40:35.767388Z`.
All 17,983 missing candidate inputs were prepared in 180 batches without failures.
The v1.3 snapshot succeeded with 29,877 inputs, 215,195,048 comparisons and no
failed articles. Snapshot construction took approximately nine minutes.

The review then stopped because an ART-048 holdout article was absent from the
published candidate. A database coverage check confirmed that **all 40 holdout
article references and all 10 ART-045 relationship article references are absent**.
This is an unavailable evaluation dataset, not a measured holdout failure.

The existing regression evaluators were also run separately against v1.3 and v1.2:

- v1.3 passes all 28 frozen component relationships; v1.2 has 21 deviations.
- Both versions pass all seven frozen input-quality cases using the corpus fallback.
- Both report the same five ART-045 relationship deviations, but none of those
  source articles exists locally, so this does not establish relationship quality
  or absence of new regressions.

Partial diagnostic evidence is in `GEM-87-review-20261117.json`. No formal release
evidence file was produced, and no holdout precision or recall is claimed. The
normal background jobs were restored; v1.2 remains `ACTIVE` and v1.3 `SHADOW`.

The fresh holdout below replaces the unavailable ART-048 holdout for the new
independent quality check. It does not restore the missing ART-045 relationship
regression inputs.

## Fresh independent holdout on 2026-11-17

Created and sealed `gem87-independent-holdout-v1.0.0` before observing predictions
for its cases: 20 positive pairs, 20 negative pairs using 80 distinct articles,
and eight input-quality cases. Article references and normalized title hashes
are disjoint from all earlier evaluation corpora. Candidate configuration, rule,
thresholds and gates were unchanged; see `GEM-87-fresh-holdout-review.md`.

The full non-promoting release workflow completed on the fixed v1.2 watermark
`2026-11-17T21:04:26.782272Z`. It prepared 728 additional inputs without failures
and published a successful v1.3 snapshot with 30,605 inputs and 215,195,048
comparisons. Final precheck found no missing/stale inputs, incomplete embeddings
or unfinished processing, and both versions had the same watermark.

| Fresh holdout result | Value |
|---|---:|
| True positives | 19 |
| False positives | 0 |
| False negatives | 1 |
| True negatives | 20 |
| Precision | 1.00 |
| Recall | 0.95 |
| Input-quality deviations | 0 / 8 |
| Fixed `story-release-gates-v2` | Passed |

The one missed merge is the pair of reports about UK sanctions on companies
involved in West Bank settlements. The label remains unchanged. All 28 component
relationships and seven frozen input-quality regression cases pass.

Formal evidence: `GEM-87-fresh-holdout-release-evidence.json`.
Case-by-case outcomes: `GEM-87-fresh-holdout-pair-results-v1.json`.
The label and candidate-configuration hashes were verified unchanged after evaluation.

Coverage limits remain explicit: the ten historical ART-045 relationship articles
are absent, so their five reported deviations do not establish a regression
comparison. Only 34 of the 40 fresh pairs have complete v1.2 memberships; no
whole-holdout v1.2 precision/recall is reported. The unadjusted story diff also
includes unequal processing coverage (14,669 v1.2 versus 29,557 v1.3 memberships).
This holdout is small and English-dominant, with labels based on source headlines
and URLs rather than full-text verification.

At the end of this non-promoting review background processing was restored;
v1.2 remained `ACTIVE` and v1.3 `SHADOW`, with no rule, label or gate adjustment.
The subsequent authorized activation is recorded below. The fresh labels
are now evaluation evidence and must not be reused as an independent holdout to
tune a changed candidate.

## Authorized activation and live verification

Linus subsequently authorized activation and verification of actual pipeline and
monitor use; see `GEM-87-activation-approval.md`. The guarded release workflow
was repeated with promotion enabled on fixed watermark
`2026-11-17T21:34:32.527134Z`, using the unchanged sealed labels and candidate.
It prepared 5,247 missing and 731 stale inputs without failed batches, built a
successful snapshot, and passed the same gates again: precision 1.00, recall
0.95, zero input-quality deviations and zero component-regression deviations.

The atomic transition at `2026-11-17T21:53:02.216585Z` made v1.3 the only
`ACTIVE` version and v1.2 `RETIRED`. The release command explicitly selected
v1.3 for its API verification. Formal activation evidence is
`GEM-87-v1.3.0-activation-evidence.json`.

Then `stories.visible-version` in the source and running application was changed
to v1.3, the engine reloaded, and normal jobs restored. Live checks in
`GEM-87-v1.3.0-runtime-verification.json` establish:

- The monitor proxy API total is exactly v1.3's 20,181 stories, versus 10,028 in
  retired v1.2. The current 24-hour window contains 11,741 stories, 3,086 located.
- A public story ID absent from v1.2 resolves through both list search and
  detail endpoints, with all 247 member IDs matching v1.3 in the database.
- The real browser monitor renders that exclusive story and its 247 articles,
  and returns to an overview with interactive map clusters and the story list.
- Normal incremental v1.3 run 92 succeeded after activation, reading 35,827
  inputs with no failures and publishing commit 92 at watermark
  `2026-11-17T21:54:27.744532Z`.
- v1.2 has zero new processing runs and zero new inputs after retirement.

The normal run had zero changed inputs. A new-article end-to-end check could
not be completed because GDELT advertises later files but returns HTTP 404 for
the 21:30, 21:45 and 22:00 EVENTS archives. Direct checks of the announced HTTP
URLs and HTTPS URLs confirm the failure. The last successful source window is
21:15 UTC. No download transport or source configuration was changed to conceal
this external limitation; normal retries remain enabled. Existing-data
processing and actual version selection are verified; fresh-input completion
is explicitly unverified while the source files remain unavailable.
