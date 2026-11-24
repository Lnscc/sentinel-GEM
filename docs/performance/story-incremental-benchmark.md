# Incremental story benchmark

This procedure compares the active implementation with GEM-122 using identical frozen inputs.
The [24 November VPS attempt](story-incremental-vps-2026-11-24.md) exceeded the five-minute budget
and was aborted after production OOM restarts. The [isolated local comparison](story-incremental-local-2026-11-24.md)
passed input/output equivalence, three scheduled intervals and concurrent API checks. VPS capacity remains unverified.
Use a dedicated environment. Do not repeat a co-located test on the live production VPS.
Small synthetic fixtures and isolated API tests cannot establish the throughput target.

GEM-122 keeps canonical snapshot metadata, exact temporal candidate comparisons and GEM-121's
streaming vector loading and lazy pair search. It rebuilds and publishes affected source/target
stories only. V39 adds a coalescing source-change queue and a current-membership index.
Article/GKG/event/mention changes invalidate projections transactionally; the active incremental
publisher resolves current memberships and skips locked invalidations until a later run. Activation
seeds the newly active version's projections. Shadow/backfill publications retain full refreshes.
Include queue writes, migration/index creation and importer latency in operational evaluation.

## Freeze and isolate the workload

Record both Git commits, Java/PostgreSQL versions, active clustering version, CPU count and quota,
memory limit, database size, storage class and cache policy. Use a separate PostgreSQL instance
with the VPS CPU, memory and storage constraints; run both variants sequentially on that same
instance. Keep engine JVM limits, API load, indexes and PostgreSQL settings identical.

Prepare one database dump immediately after a successful incremental publication at T0. Capture
at least three consecutive representative five-minute import intervals as ordered SQL replay
files, including article updates, event/mention changes and ready embedding inputs/artifacts.
Preserve primary keys, fingerprints, timestamps and current markers. Each file must contain only
upstream changes for its interval, not story outputs, snapshot tables or processing-run rows.
Record SHA-256 hashes of the dump and interval files, their UTC interval boundaries and imported
article/changed-input counts. Include corrections and changes to existing stories, not just new
isolated articles. Keep these database artifacts outside Git because they may contain secrets
and licensed content. Do not reset or replay into the production database.

Restore the same dump into `gem_before` and `gem_after` on the isolated server. For example,
with the dump already copied into a dedicated local container named `gem-benchmark`:

```powershell
docker exec gem-benchmark createdb -U gne gem_before
docker exec gem-benchmark createdb -U gne gem_after
docker exec gem-benchmark pg_restore -U gne -d gem_before --no-owner /tmp/baseline.dump
docker exec gem-benchmark pg_restore -U gne -d gem_after --no-owner /tmp/baseline.dump
```

## Build isolated comparison JARs

Build both variants in disposable source checkouts under an ignored directory. Apply these
**test harness overlays only to those checkouts**, never to the production build or deployment.
Use the active clustering version from the dump in both variants; do not promote another model.

The measured baseline was deployed commit `7e6eef62ef10677e613aad0d0087d28c5b09ceac`.
The committed [baseline instrumentation patch](../../scripts/gem122-benchmark/baseline-instrumentation.patch)
adds phase timers, deterministic watermark handling and the sequence runner without changing
clustering/publication rules. From a repository containing that commit and the GEM-122 commit:

```powershell
$patches = (Resolve-Path scripts/gem122-benchmark).Path
$before = 'engine/target/gem122-rebuild-before'
$after = 'engine/target/gem122-rebuild-after'
$optimizedCommit = '<GEM-122 commit to evaluate>'
git clone --no-hardlinks . $before
git -C $before checkout --detach 7e6eef62ef10677e613aad0d0087d28c5b09ceac
git -C $before apply --unidiff-zero (Join-Path $patches 'baseline-instrumentation.patch')
git clone --no-hardlinks . $after
git -C $after checkout --detach $optimizedCommit
git -C $after apply --unidiff-zero (Join-Path $patches 'sequence-runner.patch')
```

The patches use zero context; keep `--unidiff-zero` in both apply commands.

In the baseline checkout also add the following import and class annotation to
`engine/src/main/java/com/example/globalnewsenginev1/stories/snapshot/StoryLocationBackfill.java`:

```java
import org.springframework.core.annotation.Order;
// On the existing StoryLocationBackfill class, below @Component:
@Order(-100)
```

This makes startup backfill finish before the blocking benchmark runner and keeps startup
outside timed steps. The optimized GEM-122 source already has this ordering; its
[sequence-runner patch](../../scripts/gem122-benchmark/sequence-runner.patch) adds only the same
file-driven sequence command. Neither overlay should be committed into application sources.

Run `mvnw.cmd -DskipTests package` from each checkout's `engine` directory. Copy the resulting
`engine/target/sentinel-gem-engine-0.0.1-SNAPSHOT.jar` files into a **fresh private benchmark
directory** as `before.jar` and `after.jar`, and record their SHA-256 hashes. Compile the probe
and run the local controller as described below. The controller supplies the sequence directory,
prepares step SQL before measurement, and releases the three intervals on a 300-second schedule.

The [recorded artifact hashes](story-incremental-local-2026-11-24.md#verglichener-code-und-provenienz)
identify the actual measured JARs and source overlays. A later build from these instructions can
have different bytes or include newer fixes; its hashes and measurements must be recorded anew.
These patches make the harness reproducible, not a later build identical to the historical artifact.

## Replay and measure

Disable all scheduled writers in both the API process and benchmark process:

```powershell
$disabledJobs = @(
    '--gdelt.ingestion.enabled=false', '--gdelt.staging.enabled=false',
    '--gdelt.retention.enabled=false', '--articles.enabled=false',
    '--stories.embeddings.incremental.enabled=false', '--stories.embeddings.repair.enabled=false',
    '--stories.snapshots.incremental.enabled=false', '--stories.snapshots.backfill.enabled=false',
    '--spring.docker.compose.enabled=false'
)
```

Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` for the selected clone. Start its API with the corresponding JAR,
the disabled-job arguments and no benchmark arguments. Serve it through the same reverse proxy
configuration for both variants. Monitor URLs are `/api/articles` and `/api/stories`; direct
engine URLs are `/articles` and `/stories`, because the monitor strips `/api/`.

Warm the two read paths, then poll them in a separate PowerShell process while replaying all
intervals. The script records every status, timeout and response latency, and reports p50/p95
for successful requests separately from failures. Its `BaseUrl` must include `/api/` when using
the monitor; use the engine root when measuring the engine directly.

```powershell
.\scripts\story-incremental-benchmark.ps1 -BaseUrl http://localhost:5173/api/ `
    -OutputPath before-api.csv -DurationSeconds 1800 -RequestTimeoutSeconds 5 |
    ConvertTo-Json | Set-Content before-api-summary.json
```

For each interval, apply exactly its SQL file to the clone, then start one incremental run at
that interval's recorded UTC boundary. Copy replay files into the isolated container beforehand.
Use the matching JAR and give each invocation its own log file. From the repository root:

```powershell
docker exec gem-benchmark psql -U gne -d gem_before -v ON_ERROR_STOP=1 -f /tmp/interval-01.sql
if ($LASTEXITCODE -ne 0) { throw 'Interval replay failed' }
$jar = 'C:\benchmark\before\sentinel-gem-engine-0.0.1-SNAPSHOT.jar'
$timer = [Diagnostics.Stopwatch]::StartNew()
& java -jar $jar @disabledJobs '--spring.main.web-application-type=none' `
    '--stories.snapshots.benchmark.mode=incremental' `
    '--stories.snapshots.benchmark.watermark=2026-11-24T18:05:00Z' *> before-01.log
$timer.Stop()
$timer.Elapsed.TotalMilliseconds
```

Replace the example boundary with the actual recorded time; repeat for every interval and then
for the other clone with the identical boundaries and SQL files. Do not substitute wall-clock
time or skip a slow interval. Use each run's phase log for loading, exact comparisons and
publication durations, plus its candidate-comparison count. Also retain process elapsed time,
which includes startup. Require the log to report a successful published run, not a reused or
failed run; a successful Java exit alone does not prove this. Observe the processing-run rows:

```sql
SELECT run.id, run.run_mode, run.status, run.read_article_count,
       run.candidate_count, run.started_at, run.completed_at,
       extract(epoch FROM run.completed_at - run.started_at) AS processing_seconds
FROM story_processing_runs run
JOIN story_clustering_versions version ON version.id = run.clustering_version_id
WHERE version.status = 'ACTIVE'
ORDER BY run.id DESC LIMIT 10;
```

Schedule releases every 300 seconds in the replay timeline. Calculate publication lag from that
schedule, including earlier overruns, rather than restarting a fresh five-minute allowance after
each completion. Record pending interval count and lag after each publication. Every representative
interval must publish within 300 seconds and lag must not increase. Continue API polling across
publication transactions and require zero HTTP errors/timeouts for both endpoints. Keep startup
cost separate if the benchmark JAR is launched per interval; the production worker stays running.

## Check equivalence and publish the evidence

After each interval, export the following ordered query results from both clones and compare
them byte for byte. Use `psql --csv -c` against each clone, and save its output with the interval
and variant name. Keep internal run IDs and wall-clock audit timestamps out of the comparison.

```sql
SELECT clustering_version_id, article_ref, story_id, article_input_fingerprint
FROM story_memberships WHERE current_marker = 1
ORDER BY clustering_version_id, article_ref;

SELECT id, public_id, clustering_version_id, identity_anchor_article_ref,
       representative_article_ref, state, member_count, source_count,
       independent_report_count
FROM stories ORDER BY id;

SELECT story_id, rank, name, country_code, region_code, latitude, longitude,
       source, confidence, supporting_articles, supporting_domains
FROM story_locations ORDER BY story_id, rank;

SELECT clustering_version_id, article_ref, count(*)
FROM story_memberships WHERE current_marker = 1
GROUP BY clustering_version_id, article_ref HAVING count(*) > 1;
```

The duplicate query must return no rows. Repeat the last run with exactly the same watermark
and no upstream changes, verify reuse and unchanged exports/membership counts. Cover normal
closing transitions and a location-only event/mention update in the captured sequence or with
separate regression checks; do not rely only on assignment equality.

Report one row per interval and variant: dataset hash, input/changed counts, load milliseconds,
comparison milliseconds, publication milliseconds, candidates, total processing milliseconds,
publication lag and pending intervals. Add article/story HTTP sample counts, p50/p95, HTTP errors
and timeout counts over the same measurement window. Include resource settings, exact commits,
instrumentation patch, replay/export commands, logs and equivalence results. Label missing
measurements as missing; do not infer VPS throughput from local unit tests. The existing
`StoryApiPerformanceBudgetIT` remains a useful separate regression gate, not a concurrent-load
or production-capacity measurement.

## Local verification

For the production-sized replay, use `scripts/gem122-benchmark/run_local.py` against
the explicitly authorized, private backup copy under the Git-ignored benchmark directory.
The runner requires the local Docker named pipe, an internal network, and the matching
`gem122_before` or `gem122_after` database. Run the variants sequentially. Each engine
and PostgreSQL container receives one CPU and 2 GiB without swap; the independent API
probe receives 0.25 CPU and 128 MiB. This local comparison does not establish VPS capacity.

Compile the internal-network probe before running either variant:

```powershell
javac --release 21 -d engine/target/gem122-local-benchmark/probe-classes scripts/gem122-benchmark/ApiProbe.java
```

The probe polls both APIs every second, measures complete responses with a five-second
request timeout, and saves status, latency and response size without response contents.
No host ports or external network access are needed. `verify_local.py` checks the two
completed variant directories for frozen-input and output equivalence, clean process
exits, the publication budget, API errors and backlog. Keep the backup, environment
files and raw database contents outside Git. Do not run this workload beside production.

Against a disposable PostgreSQL database on port 55432, run from `engine`:

```powershell
.\mvnw.cmd '-Dit.postgres.jdbc-url=jdbc:postgresql://localhost:55432/gne' `
    '-Dspring.datasource.url=jdbc:postgresql://localhost:55432/gne' `
    '-Dspring.datasource.username=gne' '-Dspring.datasource.password=gne' `
    '-Dspring.datasource.driver-class-name=org.postgresql.Driver' `
    '-Dspring.jpa.hibernate.ddl-auto=none' verify
```

The default embedded H2 application-context test already fails at the PostgreSQL-specific V34
migration; use PostgreSQL for the complete migration/application checks. Integration tests skip
when their database is unavailable, so inspect the reported skipped counts.
