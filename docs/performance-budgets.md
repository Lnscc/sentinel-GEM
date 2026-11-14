# Performance budgets

`StoryApiPerformanceBudgetIT` measures the read paths used by the Lagebild against a deterministic
PostgreSQL fixture with 2,000 stories, three current articles per story, 40 domains and distributed
country and region codes. The fixture is recreated for every run.

Run the benchmark with PostgreSQL from `compose.yaml` available:

```powershell
cd engine
.\mvnw.cmd -Dtest=StoryApiPerformanceBudgetIT test
```

The benchmark uses the same `it.postgres.jdbc-url`, `it.postgres.username` and
`it.postgres.password` system properties as the other PostgreSQL integration tests.

The first request per path is reported as the cold run. The next 20 requests form the warm sample;
the test reports response and database p50/p95 plus the serialized response size. Database time is
the time spent executing JDBC statements and excludes mapping and serialization. The cold value
includes first-use JVM, query-plan and application costs, so it is observed but not used as a stable
cross-machine gate.

## Budgets

| User path | Request | Warm response p95 | Warm database p95 | Maximum response |
| --- | --- | ---: | ---: | ---: |
| Story list | `GET /stories?limit=20&sort=time&direction=desc` | 250 ms | 220 ms | 35 kB |
| Region filter / initial Lagebild | `GET /stories?limit=100&sort=articleCount&direction=desc&region=R1` | 1,200 ms | 1,100 ms | 170 kB |
| Story detail | `GET /stories/{id}` | 200 ms | 180 ms | 12 kB |

Reference measurement on 2026-11-14 (local Docker PostgreSQL 17, Java 21):

| Path | Cold response | Warm response p50/p95 | Warm database p50/p95 | Response |
| --- | ---: | ---: | ---: | ---: |
| Story list | 171 ms | 38 / 41 ms | 17 / 17 ms | 24,428 B |
| Region filter | 1,142 ms | 797 / 809 ms | 772 / 779 ms | 122,278 B |
| Story detail | 38 ms | 24 / 26 ms | 8 / 8 ms | 2,156 B |

For the browser, the initial Lagebild should become usable within 2,000 ms after navigation on a
warm local stack. A pan or zoom should render the resulting marker set within 100 ms, and selecting
a story should show its detail within 500 ms. These are interaction targets for browser profiling;
the API gate covers the server-side work behind initial loading, region changes and selection.

The budgets intentionally flag substantial regressions rather than normal workstation noise. When
a budget fails, compare the printed cold and warm values before changing it. Only update a budget
with a recorded representative measurement and an explicit product decision; do not raise it merely
to make a slow run pass.
