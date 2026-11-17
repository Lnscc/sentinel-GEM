# GEM-87: Fresh independent holdout

Labels frozen on 2026-11-17 at `21:07:29.268081Z`, before reading any predictions
for the selected cases. This review does not authorize promotion.

## Frozen candidate and gates

- Candidate: `story-mvp-title-embedding-24h-v1.3.0`, `SHADOW`.
- Component rule: `complete-linkage-template-coherent-v2`.
- Gates: unchanged `story-release-gates-v2`, at least 20 positive and 20 negative
  pairs, precision >= 0.95, recall >= 0.90, and no input-quality deviation.
- Candidate configuration SHA-256:
  `203edfdd5ea2f061eefa046769c6e9d3eb8de0ad987246ac474bee2ee2b462b6`.
- Label file SHA-256:
  `e12e5dd00588cd152540f131024ef180bbdc00385519bbdb7346c02482fd3eb3`.

The canonical labels are in `GEM-87-fresh-holdout-v1.json`; source headlines,
canonical URLs, timestamps, selection provenance and the complete frozen
candidate configuration are in `GEM-87-fresh-holdout-sources-v1.json`.

## Selection and independence

The raw pool contains 14,071 GDELT articles first seen between
`2026-11-17T18:00:00Z` and `2026-11-17T20:00:00Z`. Selection did not query story
memberships, embedding scores, model decisions or candidate outcomes. Exact
headline groups across domains and lexical token overlap supplied a blind
shortlist with fixed seeds `20261117` and `20261118`.

All 88 selected article references and normalized title hashes were checked
against ART-032, ART-044, ART-045, ART-048, GEM-103 and the GEM-87 component fixture.
There are no overlaps. Previously inspected Nvidia/Huang and known regression
event chains were excluded. Broadly similar templates are intentionally included
with new real entities; none of these labels was used to choose the rule.

There are 20 positive pairs: eight identical-headline reports from different
domains and twelve headline variants. They cover distinct incidents, official
releases, appointments, sporting developments and announcements. The 20 negative
pairs include same-template financial reports about different companies,
different government actions, separate crime incidents, different speakers and
unrelated events with identical publisher suffixes. Every pair uses two unique
articles; no article is reused across pairs.

The eight input-quality cases comprise four instructional advice pages without
GDELT event links and four specific news reports from those same domains. They
guard against treating entire domains as non-event sources.

## Labelling and limitations

Codex labelled the cases at Linus's explicit request using raw source headlines
and canonical URLs. Labels concern report/event identity rather than the factual
truth of news claims. No full-text source verification is claimed. Ambiguous
cases, liveblog pairs and advice pairs were excluded from relationship labels.
The sample is English-dominant and intentionally small; it is a release check,
not an estimate of global production accuracy.

Labels, rationales, candidate configuration and gates are frozen before the
evaluation. Failures must be reported as observed; these cases must not be
relabelled or used to tune this candidate. A rule change requires another
independent holdout.

The missing ART-045 historical relationship articles remain a separate coverage
limitation. A successful fresh holdout does not restore those regression inputs.

## Observed result

The full non-promoting workflow completed on the fixed watermark
`2026-11-17T21:04:26.782272Z`. `story-release-gates-v2` passed:

- 19 true positives, zero false positives, one false negative and 20 true negatives.
- Precision `1.00`, recall `0.95`.
- All eight input-quality cases correct.
- All 28 frozen component relationships and seven input-quality regression cases correct.

The false negative is case `L69`: the two reports about UK sanctions on companies
involved in West Bank settlements remained separate. The original positive label
was retained without modification. The label-file and candidate-configuration
hashes still match the values sealed above.

The evaluator's semantic holdout hash is
`bb738a577e334faf65e5c889930c77bb6f9232d4ccdd62dddbd56e44b3644007`.
Formal evidence is `GEM-87-fresh-holdout-release-evidence.json`; individual pair
outcomes are in `GEM-87-fresh-holdout-pair-results-v1.json`.

Only 34/40 pairs have complete memberships in the active v1.2 baseline, so no
overall baseline precision or recall is claimed. Missing historical ART-045
relationship articles remain a separate limitation. At completion of this
non-promoting review the candidate remained `SHADOW`, background jobs were
restored and v1.2 remained `ACTIVE`.

Linus subsequently explicitly authorized activation. The guarded workflow
rechecked the unchanged labels and gates on a current fixed watermark and
activated v1.3 on 2026-11-17; v1.2 is now `RETIRED`. This subsequent operation does
not alter the frozen labels or the observed results above. See
`GEM-87-activation-approval.md`, `GEM-87-v1.3.0-activation-evidence.json` and
`GEM-87-v1.3.0-runtime-verification.json` for authorization and operational proof.
