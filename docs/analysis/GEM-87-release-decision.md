# GEM-87: Release decision

Decision: do not activate or promote `story-mvp-title-embedding-24h-v1.3.0`.

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

No release evidence file was written and no holdout result is claimed. Repeat
the existing release workflow with `stories.release.promote=false` during a
controlled window where the active watermark stays fixed. Promotion requires a
separate authorization after a successful review.
