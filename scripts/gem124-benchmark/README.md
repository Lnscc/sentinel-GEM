# GEM-124 integrated Docker load test

This test runs the integrated story pipeline against a dedicated PostgreSQL database,
with concurrent HTTP probes and snapshot maintenance. It does not use the production
Docker project, its database, or external embedding services.

The envelope is **2 CPUs and 3,200 MiB across both containers**, with a further
512 MiB reserved when assessing the reference host budget:

| Service | CPU quota | Memory limit |
| --- | ---: | ---: |
| PostgreSQL | 0.75 | 1,152 MiB |
| Engine, fixture and HTTP probes | 1.25 | 2,048 MiB |

Container swap is disabled. PostgreSQL binds only `127.0.0.1:55424`; its named
volume is `gem124-benchmark-postgres-data`. The network has no external route.
The database password is a disposable fixture value, never a production credential.

## Prepare

Use a clean checkout of the integrated source, Java 21 or newer with Java 21 target
bytecode, Python 3, and a local Linux Docker engine. Docker Desktop is supported
for the local run. Stop unrelated load tests first; the preflight rejects a
non-idle Docker host. Compilation is outside measured intervals.

From `engine` on Windows:

```powershell
./mvnw.cmd -q -DskipTests test-compile dependency:build-classpath `
  '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/gem124-benchmark/classpath.txt'
```

From the repository root:

```powershell
python -m unittest discover -s scripts/gem124-benchmark -p 'test_*.py'
python scripts/gem124-benchmark/run_local.py --name smoke `
  --inputs 1070 --changes 10 --intervals 3 --interval-seconds 20
python scripts/gem124-benchmark/run_local.py --name dense
python scripts/gem124-benchmark/verify.py engine/target/gem124-benchmark --name dense
```

Use a fresh `--name` for each run; existing evidence is never overwritten.
`--history-inputs 20000` additionally persists independent noncurrent inputs from
90 days before the frozen epoch. They share an existing valid embedding artifact
and remain outside the logical snapshot population; the default is zero for
reproducing the original measurement. This option was verified with a small
reference/integrated run and does not by itself qualify the 107k VPS run.
Generated dependency and reference-class directories are rebuilt before each run.
Each variant has a deadline of at least 30 minutes (or three times the configured
release window, whichever is larger); the runner stops its containers on timeout.

The default runner image is the already available Java-21 image
`sentinel-gem-engine:gem116-check`. Its bundled old application is never started:
the command explicitly loads the freshly compiled classes from this checkout.
Use `--image` to supply another installed Java-21 image. Resolved image IDs and
the source commit are recorded in the manifest.

The full run uses at least 107,000 inputs, 1,000 changes and three releases spaced
300 seconds apart. Reference and integrated variants run sequentially on frozen
inputs. The reference uses the full vector loader and frozen scalar comparison
classes; it is a computational reference, not an unchanged pre-GEM-125 deployment.
Retention keeps its real 30-minute age rule, with five full historical snapshots
prewarmed for cleanup. It polls every 30 seconds in the full stress test (twice the
normal frequency) to exercise overlap with publication; smoke uses two seconds.

## Evidence and limits

Reports and logs are written under `engine/target/gem124-benchmark`. The manifest
includes actual CPU/RAM, resource limits, image IDs, source provenance, exit codes
and container OOM state. The named database volume remains available for diagnosis;
the runner stops only its own Compose services and never removes volumes.

The small smoke fixture validates the harness, not throughput. The large fixture
is deterministic, dense synthetic load with controlled similar articles and small
groups. Its distinction from real production replay and any untested import stages
must remain visible in the result. Do not infer provider or network latency from
locally supplied data.

GEM-124 now explicitly accepts the isolated Docker environment under the above
combined CPU/memory budget, with recorded host hardware and 512 MiB system reserve.
Start the approved load qualification with:

```powershell
python scripts/gem124-benchmark/run_local.py --name docker-acceptance `
  --docker-acceptance --inputs 107000 --changes 1000 --history-inputs 20000 `
  --intervals 3 --interval-seconds 300
python scripts/gem124-benchmark/verify.py engine/target/gem124-benchmark --name docker-acceptance
```

The verifier requires actual five-minute releases, dense workload, matching frozen
inputs/results, independent history, enforced limits including disabled additional
swap, maintenance progress and successful concurrent API/import probes. Only a
full qualifying run can set `dockerLoadAcceptancePassed`; smoke cannot.
The other functional and storage-cycle evidence must still be assessed for the
complete ticket acceptance. `vpsAcceptancePassed` remains false: CPU quotas do not
establish identical latency on the production Xeon. The legacy native-host check
in `--mode acceptance` is optional and is no longer a GEM-124 prerequisite.
No additional testserver is provisioned.
