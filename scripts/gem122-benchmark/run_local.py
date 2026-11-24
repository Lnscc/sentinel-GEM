"""Run one GEM-122 comparison variant on Windows local Docker, with no VPS access."""
import argparse
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import run_variant as shared


MEMORY = 2 * 1024 ** 3


def require_local_context():
    def local_pipe(endpoint):
        return endpoint.lower().replace("\\", "/").startswith("npipe:////./pipe/")

    override = os.environ.get("DOCKER_HOST")
    if override and not local_pipe(override):
        raise RuntimeError("DOCKER_HOST must use a local Windows named pipe; remote Docker is forbidden")
    inspected = shared.docker("context", "inspect", "--format", "{{json .Endpoints.docker.Host}}",
                              capture_output=True, text=True)
    endpoint = json.loads(inspected.stdout)
    if not isinstance(endpoint, str) or not local_pipe(endpoint):
        raise RuntimeError("Docker context must use a local Windows named pipe; SSH/TCP contexts are forbidden")
    return override or endpoint


def limits(name):
    config = json.loads(shared.docker("inspect", "--format", "{{json .HostConfig}}", name,
                                     capture_output=True, text=True).stdout)
    return {key: config.get(key) for key in ("NanoCpus", "Memory", "MemorySwap", "PidsLimit")}


def require_limits(name, expected_swap=None):
    actual = limits(name)
    if actual["NanoCpus"] != 1_000_000_000 or actual["Memory"] != MEMORY:
        raise RuntimeError(f"{name} must have exactly one CPU and 2 GiB RAM")
    if expected_swap is not None and actual["MemorySwap"] != expected_swap:
        raise RuntimeError(f"{name} must have its memory-plus-swap cap set to 2 GiB")
    return actual


def prepare_env(bench, variant, database):
    values = {}
    allowed = {"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"}
    for line in (bench / "db.env").read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key not in allowed or key in values:
            raise ValueError("db.env must contain only the three unique datasource settings")
        values[key] = value
    if set(values) != allowed:
        raise ValueError("db.env is missing a required datasource setting")
    url = values["SPRING_DATASOURCE_URL"]
    prefix = "jdbc:postgresql://gem122-local-postgres:5432/gem122_seed"
    if not (url == prefix or url.startswith(prefix + "?")):
        raise ValueError("Datasource must target the local gem122-local-postgres seed database")
    values["SPRING_DATASOURCE_URL"] = url.replace("/gem122_seed", "/" + database, 1)
    destination = bench / (variant + ".env")
    destination.write_text("".join(key + "=" + value + "\n" for key, value in values.items()))
    destination.chmod(0o600)
    return destination


def require_local_database(args):
    if args.postgres_container != "gem122-local-postgres" or args.database != "gem122_" + args.variant:
        raise ValueError("Evidence queries may only use the matching isolated local benchmark database")


def semantic_environment(args):
    require_local_database(args)
    sql = """SELECT json_build_object(
        'postgresVersion', version(),
        'postgresSettings', (SELECT json_object_agg(name, setting) FROM pg_settings
            WHERE name IN ('server_version_num', 'server_encoding', 'client_encoding', 'TimeZone', 'DateStyle',
                'shared_buffers', 'work_mem',
                'maintenance_work_mem', 'effective_cache_size', 'max_connections',
                'max_parallel_workers_per_gather', 'max_parallel_workers', 'max_worker_processes',
                'random_page_cost', 'seq_page_cost', 'jit', 'track_io_timing', 'synchronous_commit',
                'fsync', 'full_page_writes', 'checkpoint_timeout', 'default_statistics_target',
                'effective_io_concurrency', 'default_transaction_isolation')),
        'clusteringVersion', (SELECT row_to_json(value) FROM (
            SELECT version_key, embedding_dimension, candidate_window_hours,
                candidate_similarity_threshold, candidate_search_mode, component_rule_version,
                pair_decision_rule_version, title_normalization_version, generic_title_rule_version,
                input_eligibility_rule_version, embedding_model_id, embedding_model_version,
                candidate_time_rule_version, feature_normalization_versions
            FROM story_clustering_versions WHERE status = 'ACTIVE'
        ) value))"""
    output = shared.docker("exec", args.postgres_container, "psql", "-X", "-q", "-A", "-t",
                           "-v", "ON_ERROR_STOP=1", "-U", args.postgres_user, "-d", args.database,
                           "-c", sql, capture_output=True, text=True)
    return json.loads(output.stdout)


def database_evidence(args, expected_watermark):
    require_local_database(args)
    parsed = datetime.fromisoformat(expected_watermark.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Evidence watermark must contain a UTC offset")
    watermark = parsed.isoformat()
    result = shared.database_evidence(args, watermark)
    if result.get("snapshotMatchesStep") is not True:
        raise RuntimeError("Database output snapshot does not match the requested processing step")
    selected = f"""SELECT snapshot.id, snapshot.snapshot_input_hash, snapshot.snapshot_watermark
        FROM story_publish_commits commit JOIN story_snapshots snapshot ON snapshot.id = commit.snapshot_id
        JOIN story_clustering_versions version ON version.id = commit.clustering_version_id
        WHERE version.status = 'ACTIVE' AND snapshot.snapshot_watermark = '{watermark}'::timestamptz
        ORDER BY commit.id DESC LIMIT 1"""
    sql = f"""BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
        SELECT '__GEM122_input_metadata__';
        WITH selected AS ({selected})
        SELECT json_build_object('snapshotId', selected.id, 'snapshotInputHash', selected.snapshot_input_hash,
            'watermark', selected.snapshot_watermark,
            'count', (SELECT COUNT(*) FROM story_snapshot_members member WHERE member.snapshot_id = selected.id),
            'compacted', EXISTS (SELECT 1 FROM story_snapshot_retention archive WHERE archive.snapshot_id = selected.id))
        FROM selected;
        SELECT '__GEM122_input_rows__';
        COPY (WITH selected AS ({selected})
            SELECT member.article_ref, member.article_input_fingerprint, member.embedding_artifact_id,
                member.vector_hash, member.normalized_title
            FROM story_snapshot_members member JOIN selected ON selected.id = member.snapshot_id
            ORDER BY member.article_ref) TO STDOUT WITH CSV;
        SELECT '__GEM122_input_end__'; COMMIT;"""
    process = subprocess.Popen(["docker", "exec", args.postgres_container, "psql", "-X", "-q", "-A", "-t",
                                "-v", "ON_ERROR_STOP=1", "-U", args.postgres_user, "-d", args.database,
                                "-c", sql], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    metadata, section, size = None, None, 0
    digest = hashlib.sha256()
    for line in process.stdout:
        if line.startswith(b"__GEM122_input_") and line.rstrip().endswith(b"__"):
            section = line.decode().strip()
        elif section == "__GEM122_input_metadata__" and line.strip():
            metadata = json.loads(line)
        elif section == "__GEM122_input_rows__":
            digest.update(line)
            size += len(line)
    process.stderr.read()
    if process.wait() != 0 or not metadata:
        raise RuntimeError("Frozen input evidence export failed")
    result["snapshotInputHash"] = metadata.pop("snapshotInputHash")
    result["snapshotMembers"] = dict(metadata, sha256=digest.hexdigest(), bytes=size)
    return result


def finish_container(name, directory, stop_file, outcome, wait_seconds, state_key, log_file, forced_key):
    (directory / stop_file).touch()
    forced = False
    try:
        shared.docker("wait", name, capture_output=True, timeout=wait_seconds)
    except Exception as exception:
        forced = True
        outcome.update(success=False, teardownError=name + " did not exit naturally: " + type(exception).__name__)
        try:
            shared.docker("stop", "--time", "10", name, capture_output=True, timeout=15)
        except Exception as cleanup:
            outcome["cleanupError"] = "Could not stop " + name + ": " + type(cleanup).__name__
    outcome[forced_key] = forced
    try:
        state = json.loads(shared.docker("inspect", "--format", "{{json .State}}", name,
                                        capture_output=True, text=True, timeout=10).stdout)
        outcome[state_key] = {key: state[key] for key in ("Running", "OOMKilled", "ExitCode")}
        if state["Running"] or state["OOMKilled"] or state["ExitCode"] != 0:
            outcome.update(success=False, teardownError=name + " did not exit cleanly")
    except Exception as exception:
        outcome.update(success=False, teardownError="Cannot verify final state of " + name + ": " + type(exception).__name__)
    try:
        with (directory / log_file).open("wb") as destination:
            shared.docker("logs", name, stdout=destination, stderr=subprocess.STDOUT, timeout=15)
    except Exception as exception:
        outcome.update(success=False, logCaptureError=type(exception).__name__)


def run(args):
    if args.database != "gem122_" + args.variant:
        raise ValueError("Only the matching isolated gem122_before/gem122_after database is allowed")
    if args.postgres_container != "gem122-local-postgres" or args.network != "gem122-local":
        raise ValueError("Only the gem122-local-postgres container and gem122-local network are allowed")
    if args.basis_snapshot <= 0 or not 1024 <= args.port <= 65535:
        raise ValueError("A positive basis snapshot and an unprivileged local port are required")
    endpoint = require_local_context()
    os.environ["DOCKER_HOST"] = endpoint
    os.environ.pop("DOCKER_CONTEXT", None)  # Pin every child CLI to the validated local pipe.
    shared.require_offline_host()
    postgres_limits = require_limits(args.postgres_container, expected_swap=MEMORY)
    postgres_state = json.loads(shared.docker("inspect", "--format", "{{json .State}}", args.postgres_container,
                                             capture_output=True, text=True).stdout)
    networks = json.loads(shared.docker("inspect", "--format", "{{json .NetworkSettings.Networks}}",
                                       args.postgres_container, capture_output=True, text=True).stdout)
    if not postgres_state["Running"] or args.network not in networks:
        raise RuntimeError("Local PostgreSQL must be running on gem122-local")
    internal = json.loads(shared.docker("network", "inspect", "--format", "{{json .Internal}}", args.network,
                                       capture_output=True, text=True).stdout)
    if internal is not True:
        raise RuntimeError("gem122-local must be an internal Docker network")
    bench = args.bench_dir.resolve()
    directory = bench / args.variant
    jar = bench / (args.variant + ".jar")
    probe_classes = (args.probe_classes or (bench / "probe-classes")).resolve()
    if not jar.is_file() or not (probe_classes / "ApiProbe.class").is_file():
        raise ValueError("Benchmark jar or compiled probe-classes/ApiProbe.class is missing")
    directory.mkdir(exist_ok=True)
    if list(directory.glob("step-*")) or any((directory / name).exists()
            for name in ("api.ready", "api.stop", "api.finished")):
        raise ValueError("Variant directory already has steps/probe state; use a fresh benchmark directory")
    env_path = prepare_env(bench, args.variant, args.database)
    digest = hashlib.sha256()
    with jar.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    name = "gem122-local-" + args.variant
    created = probe_created = False
    probe_name = "gem122-local-probe-" + args.variant
    started, last_stats, next_step = time.monotonic(), -30, 0
    outcome = {"variant": args.variant, "database": args.database, "startedAt": shared.utc().isoformat(),
               "success": False, "environment": "Windows-local-Docker", "dockerEndpoint": endpoint,
               "image": args.image, "jarSha256": digest.hexdigest(), "basisSnapshot": args.basis_snapshot,
               "postgresLimits": postgres_limits, "apiEndpoint": "http://" + name + ":8080",
               "hostPortsPublished": False,
               "probeClassSha256": hashlib.sha256((probe_classes / "ApiProbe.class").read_bytes()).hexdigest()}

    def check(allow_stats=True):
        nonlocal last_stats
        now = time.monotonic()
        if now - started >= 3600:
            raise TimeoutError("Overall local benchmark limit of 3600 seconds reached")
        if allow_stats and now - last_stats >= 30:
            monitored = [name, args.postgres_container] + ([probe_name] if probe_created else [])
            output = shared.docker("stats", "--no-stream", "--format", "{{json .}}", *monitored,
                                   capture_output=True, text=True).stdout
            sample = {"utc": shared.utc().isoformat(),
                      "containers": [json.loads(line) for line in output.splitlines() if line.strip()]}
            state = json.loads(shared.docker("inspect", "--format", "{{json .State}}", name,
                                            capture_output=True, text=True).stdout)
            sample["engineState"] = {key: state[key] for key in ("Running", "OOMKilled", "ExitCode")}
            probe_state = None
            if probe_created:
                probe_state = json.loads(shared.docker("inspect", "--format", "{{json .State}}", probe_name,
                                                      capture_output=True, text=True).stdout)
                sample["probeState"] = {key: probe_state[key] for key in ("Running", "OOMKilled", "ExitCode")}
            with (directory / "resources.jsonl").open("a") as destination:
                destination.write(json.dumps(sample) + "\n")
            last_stats = now
            if not state["Running"]:
                raise RuntimeError(f"Local benchmark engine exited: OOMKilled={state['OOMKilled']}, exit={state['ExitCode']}")
            if probe_state is not None and not probe_state["Running"]:
                raise RuntimeError(f"Internal API probe exited: OOMKilled={probe_state['OOMKilled']}, exit={probe_state['ExitCode']}")

    def release(step, watermark, sql):
        nonlocal next_step
        (directory / f"step-{step}.sql").write_text(sql)
        temporary = directory / f"step-{step}.watermark.tmp"
        temporary.write_text(watermark + "\n")
        temporary.replace(directory / f"step-{step}.watermark")
        next_step = max(next_step, step + 1)

    def read_result(step):
        path = directory / f"step-{step}.result.json"
        if not path.exists():
            return None
        result = json.loads(path.read_text())
        if result.get("error") or result.get("failed", 0) or result.get("succeeded", 0) != 1:
            raise RuntimeError(f"Step {step} did not publish successfully")
        return result

    try:
        flags = ["--spring.docker.compose.enabled=false", "--spring.jpa.hibernate.ddl-auto=none",
                 "--gdelt.ingestion.enabled=false", "--gdelt.staging.enabled=false", "--gdelt.retention.enabled=false",
                 "--articles.enabled=false", "--stories.embeddings.incremental.enabled=false",
                 "--stories.embeddings.repair.enabled=false", "--stories.snapshots.incremental.enabled=false",
                 "--stories.snapshots.backfill.enabled=false", "--stories.snapshots.retention.enabled=false",
                 "--stories.hnsw-shadow.enabled=false", "--stories.visible-version=story-mvp-title-embedding-24h-v1.1.0",
                 "--stories.snapshots.benchmark.watermark=" + shared.INITIAL,
                 "--stories.snapshots.benchmark.sequence-dir=/sequence"]
        shared.docker("run", "--detach", "--pull", "never", "--name", name, "--cpus", "1", "--memory", "2g", "--memory-swap", "2g",
                      "--pids-limit", "256", "--network", args.network,
                      "--env-file", str(env_path), "--mount", f"type=bind,src={jar},dst=/app/app.jar,readonly",
                      "--mount", f"type=bind,src={directory},dst=/sequence", "--entrypoint", "java", args.image,
                      "-Xms128m", "-Xmx1024m", "-XX:ActiveProcessorCount=1", "-XX:MaxDirectMemorySize=96m",
                      "-jar", "/app/app.jar", *flags, capture_output=True)
        created = True
        outcome["engineLimits"] = require_limits(name, expected_swap=MEMORY)
        base_url = "http://" + name + ":8080"
        shared.docker("run", "--detach", "--pull", "never", "--name", probe_name, "--cpus", "0.25",
                      "--memory", "128m", "--memory-swap", "128m", "--pids-limit", "128",
                      "--network", args.network,
                      "--mount", f"type=bind,src={probe_classes},dst=/probe,readonly",
                      "--mount", f"type=bind,src={directory},dst=/output", "--entrypoint", "java", args.image,
                      "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=1", "-XX:MaxDirectMemorySize=16m",
                      "-XX:MaxMetaspaceSize=32m", "-XX:ReservedCodeCacheSize=16m", "-XX:TieredStopAtLevel=1",
                      "-cp", "/probe", "ApiProbe", base_url, "/output", "3600", capture_output=True)
        probe_created = True
        probe_limits = limits(probe_name)
        if (probe_limits["NanoCpus"] != 250_000_000 or probe_limits["Memory"] != 128 * 1024 ** 2
                or probe_limits["MemorySwap"] != 128 * 1024 ** 2):
            raise RuntimeError("Internal API probe must have 0.25 CPU and 128 MiB memory/swap limits")
        outcome["probeLimits"] = probe_limits
        readiness_deadline = time.monotonic() + 120
        while not (directory / "api.ready").exists():
            check()
            if time.monotonic() >= readiness_deadline:
                raise TimeoutError("Internal API probe did not receive an articles HTTP 200 within 120 seconds")
            time.sleep(1)
        verified = shared.preflight(args)
        verified["environment"] = semantic_environment(args)
        shared.write_json(directory / "preflight.json", verified)
        if not verified["basisSnapshotExists"] or verified["omittedCurrentMembers"]:
            raise RuntimeError("Preflight failed: immutable basis missing or current published members omitted")
        release(0, shared.INITIAL, "SELECT 1;")
        warmup_release = time.monotonic()
        while not read_result(0):
            check()
            if time.monotonic() - warmup_release > 1800:
                raise TimeoutError("Local warm-up did not complete within 1800 seconds")
            time.sleep(1)
        shared.write_json(directory / "step-0.database.json", database_evidence(args, shared.INITIAL))
        base_monotonic, base_wall = time.monotonic(), shared.utc()
        schedule = {str(step): (base_wall + timedelta(seconds=(step - 1) * 300)).isoformat() for step in (1, 2, 3)}
        shared.write_json(directory / "schedule.json", schedule)
        released, captured = {}, set()
        while len(captured) < 3:
            now = time.monotonic()
            for step, cutoff in ((1, "08:00:00Z"), (2, "08:05:00Z"), (3, "08:10:00Z")):
                planned = base_monotonic + (step - 1) * 300
                if step not in released and now >= planned:
                    release(step, shared.DAY + cutoff, shared.replay_sql(shared.DAY + cutoff, args.basis_snapshot))
                    released[step] = time.monotonic()
                    with (directory / "releases.jsonl").open("a") as destination:
                        destination.write(json.dumps({"step": step, "releasedAt": shared.utc().isoformat(),
                                                      "latenessMs": (released[step] - planned) * 1000}) + "\n")
                if step in released and step not in captured:
                    result = read_result(step)
                    if result:
                        shared.write_json(directory / f"step-{step}.database.json",
                                          database_evidence(args, result["watermark"]))
                        captured.add(step)
                    elif now - released[step] > 1800:
                        raise TimeoutError(f"Step {step} did not complete within 1800 seconds")
            if (directory / "api.finished").exists():
                raise RuntimeError("Internal API probe finished before measurement completed")
            future = [base_monotonic + (step - 1) * 300 for step in (1, 2, 3) if step not in released]
            check(allow_stats=not future or min(future) - time.monotonic() > 3)
            time.sleep(min(1, max(0, min(future) - time.monotonic())) if future else 1)
        outcome["success"] = True
    except Exception as exception:
        outcome.update(error=type(exception).__name__, reason=str(exception))
    finally:
        if probe_created:
            finish_container(probe_name, directory, "api.stop", outcome, 15,
                             "finalProbeState", "api-probe.log", "forcedProbeStop")
        if created:
            finish_container(name, directory, f"step-{next_step}.stop", outcome, 30,
                             "finalEngineState", "engine.log", "forcedEngineStop")
        outcome["completedAt"] = shared.utc().isoformat()
        shared.write_json(directory / "runner.result.json", outcome)
        print(json.dumps(outcome))
    return 0 if outcome["success"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("variant", choices=("before", "after"))
    parser.add_argument("--bench-dir", type=Path, required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, default=58082, help="Legacy unused option; no host ports are published")
    parser.add_argument("--probe-classes", type=Path, help="Compiled ApiProbe classes; defaults to bench-dir/probe-classes")
    parser.add_argument("--image", default="sentinel-gem-engine:latest")
    parser.add_argument("--network", default="gem122-local")
    parser.add_argument("--postgres-container", default="gem122-local-postgres")
    parser.add_argument("--postgres-user", default="gne")
    parser.add_argument("--basis-snapshot", type=int, default=79)
    sys.exit(run(parser.parse_args()))


if __name__ == "__main__":
    main()
