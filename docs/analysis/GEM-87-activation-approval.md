# GEM-87: Version 1.3 activation approval

On 2026-11-17 Linus explicitly authorized activation of the unchanged version 1.3:

> dann aktiviere 1.3 und prüf, dass die Pipeline und der Monitor sie wirklich verwenden
> und 1.2 kann aus oder?

This authorizes the guarded promotion of
`story-mvp-title-embedding-24h-v1.3.0`, retirement of version 1.2, updating the
application's visible version, and verification of the live pipeline and monitor.
Historical version 1.2 data is retained. This is not authorization to change the
clustering rule, holdout labels or release gates.

The fresh independent holdout passed with precision 1.00 and recall 0.95;
all eight input-quality cases and 28 component relationships passed. Known
coverage limits are recorded in `GEM-87-fresh-holdout-review.md`. Run the guarded
workflow again on a stable current watermark before promotion; failed gates
must still prevent activation.

Operational completion requires more than non-empty responses: API totals and
story details must be attributable to version 1.3, a new normal pipeline run must
publish version 1.3 after activation, and version 1.2 must be `RETIRED` with no
new regular processing. Record the resulting activation and runtime evidence.
