# Isolated statistics history benchmark

This synthetic fixture contains 194,002 logical article inputs, 264 fully
archived snapshots of 194,000 IDs each, a partially compacted snapshot, a
current snapshot, an excluded end-boundary snapshot, and another version.
Repeated successful runs and overlapping memberships exercise exact union
semantics. Archived IDs use the same sorted delta arrays and SQL view as V37.1.
The other report tables contain synthetic inventory values; GDELT payload
history and the real pipeline-health view are not reproduced.

Start Docker Desktop, then from the repository root:

```powershell
python scripts/gem128-statistics/benchmark.py --container gem128-statistics-pg17 --port 55440 --output engine/target/gem128-statistics-before-pg17.json
$env:GEM128_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55440/postgres'
```

Run `StatisticsHistoryPostgresIT` with the repository Maven setup. It creates
and removes its own schema, runs the actual optimized counter, compares small
periods against the exact SQL reference, and exercises the full report through
MockMvc including HTTP 200 and the article counts. The fixture database is only
bound to localhost and uses test-only credentials. Remove its container with
`docker rm -f gem128-statistics-pg17` after finishing. The Python runner also accepts
`--remove`, but that prevents subsequently using the same server for Java tests.

The PostgreSQL container has 0.75 CPU and 1,152 MiB RAM with no extra swap.
Maven/MockMvc runs on the host; those results alone do **not** establish the
engine's 1.25 CPU / 2 GiB limit or a real browser rendering pass. For the engine
resource limit, run the production controller/service HTTP harness:

```powershell
python scripts/gem128-statistics/probe.py --output engine/target/gem128-statistics-http.json
```

This uses current `target/classes`, dependency JARs from the already packaged
engine JAR, `javac`, and the existing local `sentinel-gem-engine:latest` runtime
image. A direct isolated Docker network joins the engine and PostgreSQL. It
also checks real article-list and story-list HTTP responses during statistics.
Remove `gem128-statistics-engine` and `gem128-statistics-network` after stopping
both benchmark containers. The harness does not test browser rendering.

A measured reference below 60 seconds in this fixture also does not disprove the observed
production timeout: its source-domain and pipeline-health work are simpler.
