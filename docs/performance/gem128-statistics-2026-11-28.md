# GEM-128 isolated statistics verification

Measured 28 November 2026 with PostgreSQL 17.12 and Java 21.0.12.1. This is a
synthetic, isolated Docker test; production was not loaded, reset or changed.

The fixture has 194,002 logical article inputs, 1,000 stories, 264 full archives
of 194,000 delta-encoded IDs each, one partial archive, one current snapshot,
and archived sentinels outside the end boundary and in another version.
Duplicate successful runs and overlapping articles must not increase the count.
The ordinary 24-hour report therefore contains exactly 194,002 processed articles.
The same production controller, service, counter and transaction advice are used
by the HTTP harness, with the clock fixed to the fixture's period end.

| Container | CPU limit | RAM limit | Additional swap |
|---|---:|---:|---:|
| Engine | 1.25 | 2,048 MiB; JVM heap 1,536 MiB | none |
| PostgreSQL | 0.75 | 1,152 MiB | none |

The containers communicate through a direct isolated Docker network. Both use
the same Windows Docker Desktop host; no additional test server is required.

| Full `/api/statistics` request | HTTP | Time | Distinct processed articles |
|---|---:|---:|---:|
| 1 | 200 | 4.391 s | 194,002 |
| 2 | 200 | 3.875 s | 194,002 |
| 3 | 200 | 2.734 s | 194,002 |

During each statistics request the actual article-list and story-list API
controllers also returned HTTP 200. Article-list times were 235, 78 and 15 ms;
story-list times were 93, 62 and 79 ms. The lists use 20 items per request.
Sampled RAM was at most 388.5 MiB for the engine and 176 MiB for PostgreSQL;
these periodic samples are not a peak-memory measurement.

The previous exact SQL reconstruction returned the same 194,002 count in
31.281 s on the PostgreSQL container. Its partial/current period returned 3
and its empty period 0. That baseline measures only the expensive count query;
the optimized HTTP numbers above cover the entire report and serialization.
The fixture does not reproduce the observed production HTTP 504: the remaining
source/pipeline tables are synthetic, and its pipeline-health relation is a
small table rather than the real production view. The result establishes the
required history size and resource budget for this reproducible workload, not
identical timing on the deployed database or disk.

The PostgreSQL integration tests separately compare exact reference counts for
fully archived, partially compacted, current, empty, boundary and other-version
periods. Deadline/cancellation and pool-release regression tests are separate
from these throughput measurements; consult the implementation's test results.
The current Monitor was additionally opened at 1280 × 900 in headless Chromium,
against this isolated HTTP engine, without mocking the statistics response.
The 24-hour request returned HTTP 200; the page displayed `194.002`, its stock
section and the active version, with no error alert. The screenshot was visually
checked: [Monitor evidence](gem128-statistics-ui-2026-11-28.png).

All 24 focused Java tests passed, including six PostgreSQL cases. These cover
deadline and detected abort, connection reuse, cancellation isolation, an archive
read blocked after snapshot selection, exact history semantics and HTTP errors.
The Statistics Postman collection parses as valid JSON and checks the unchanged
200/400/503 response shapes and the gateway deadline.

Reproduction and fixture details: [benchmark instructions](../../scripts/gem128-statistics/README.md).
Raw evidence: [previous exact SQL](gem128-statistics-reference-2026-11-28.json),
[optimized HTTP requests](gem128-statistics-http-2026-11-28.json). The latter
records the engine image ID, PostgreSQL version and hashes of the actual
compiled production statistics classes used by the test.
