"""Run one isolated GEM-122 VPS benchmark variant; never targets the production database."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import urllib.request


DAY = "2026-11-24T"
INITIAL = DAY + "07:55:00.000001Z"
IMAGE = "sentinel-gem-vps-engine:7e6eef62ef10677e613aad0d0087d28c5b09ceac"
MIN_HOST_RESERVE_MIB = 512


def utc():
    return datetime.now(timezone.utc)


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


def replay_sql(cutoff, basis_snapshot=79):
    if not isinstance(basis_snapshot, int) or basis_snapshot <= 0:
        raise ValueError("A positive immutable basis snapshot ID is required")
    return f"""
WITH chosen AS MATERIALIZED (
    SELECT DISTINCT ON (replay.article_ref) replay.id,
        CASE WHEN input.input_disposition <> 'INCLUDE' OR input.title_usability <> 'USABLE'
             THEN replay.embedding_status
             WHEN artifact.status = 'READY' AND artifact.ready_at <= '{cutoff}'::timestamptz
             THEN 'READY' ELSE 'PENDING' END AS desired_status
    FROM gem122_replay_inputs replay
    JOIN story_article_inputs input ON input.id = replay.id
    LEFT JOIN story_embedding_artifacts artifact ON artifact.id = replay.embedding_artifact_id
    WHERE replay.created_at <= '{cutoff}'::timestamptz
       OR replay.id IN (SELECT article_input_id FROM story_snapshot_members WHERE snapshot_id = {basis_snapshot})
    ORDER BY replay.article_ref, (replay.created_at <= '{cutoff}'::timestamptz) DESC,
             replay.created_at DESC, replay.id DESC
), ended AS (
    UPDATE story_article_inputs input SET current_marker = NULL, superseded_at = '{cutoff}'::timestamptz
    WHERE input.current_marker = 1 AND input.id IN (SELECT id FROM gem122_replay_inputs)
      AND NOT EXISTS (SELECT 1 FROM chosen WHERE chosen.id = input.id)
    RETURNING input.id
)
UPDATE story_article_inputs input
SET current_marker = 1, superseded_at = NULL, embedding_status = chosen.desired_status, next_retry_at = NULL
FROM chosen, (SELECT COUNT(*) FROM ended) completed
WHERE input.id = chosen.id AND (input.current_marker IS DISTINCT FROM 1
    OR input.superseded_at IS NOT NULL OR input.embedding_status <> chosen.desired_status
    OR input.next_retry_at IS NOT NULL);
"""


def docker(*arguments, **kwargs):
    return subprocess.run(["docker", *arguments], check=True, **kwargs)


def require_offline_host():
    inspected = subprocess.run(["docker", "inspect", "--format", "{{json .State.Running}}",
                                "sentinel-gem-vps-engine-1"], capture_output=True, text=True)
    if inspected.returncode:
        if "no such object" in inspected.stderr.lower() or "no such container" in inspected.stderr.lower():
            return
        raise RuntimeError("Could not verify production container state; use a dedicated or offline benchmark host")
    running = json.loads(inspected.stdout)
    if not isinstance(running, bool) or running:
        raise RuntimeError("Production engine is running; use a dedicated or offline benchmark host")


def host_available_kib():
    path = Path("/proc/meminfo")
    if not path.exists():
        raise RuntimeError("This runner must execute on the Linux benchmark host")
    return int(re.search(r"^MemAvailable:\s+(\d+)", path.read_text(), re.M)[1])


def preflight(args):
    active = "(SELECT id FROM story_clustering_versions WHERE status = 'ACTIVE')"
    sql = f"""SELECT json_build_object(
        'basisSnapshot', {args.basis_snapshot},
        'basisSnapshotExists', EXISTS (SELECT 1 FROM story_snapshots
            WHERE id = {args.basis_snapshot} AND clustering_version_id = {active}),
        'basisMembers', (SELECT COUNT(*) FROM story_snapshot_members WHERE snapshot_id = {args.basis_snapshot}),
        'omittedCurrentMembers', (SELECT COUNT(*) FROM story_memberships member
            WHERE member.current_marker = 1 AND member.clustering_version_id = {active}
              AND NOT EXISTS (SELECT 1 FROM story_article_inputs input
                WHERE input.clustering_version_id = member.clustering_version_id
                  AND input.article_ref = member.article_ref AND input.current_marker = 1
                  AND input.input_disposition = 'INCLUDE')),
        'historicalBasisFallbackRefs', (SELECT COUNT(*) FROM story_memberships member
            WHERE member.current_marker = 1 AND member.clustering_version_id = {active}
              AND NOT EXISTS (SELECT 1 FROM gem122_replay_inputs replay
                WHERE replay.article_ref = member.article_ref AND replay.created_at <= '{INITIAL}'::timestamptz)))"""
    output = docker("exec", args.postgres_container, "psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
                    "-U", args.postgres_user, "-d", args.database, "-c", sql, capture_output=True, text=True)
    return json.loads(output.stdout)


def database_evidence(args, expected_watermark):
    expected_watermark = datetime.fromisoformat(expected_watermark.replace("Z", "+00:00")).isoformat()
    active = "(SELECT id FROM story_clustering_versions WHERE status = 'ACTIVE')"
    queries = {
        "memberships": f"""SELECT clustering_version_id, article_ref, story_id, article_input_fingerprint
            FROM story_memberships WHERE current_marker = 1 AND clustering_version_id = {active}
            ORDER BY clustering_version_id, article_ref""",
        "stories": f"""SELECT id, public_id, clustering_version_id, identity_anchor_article_ref,
            representative_article_ref, state, effective_from, effective_to,
            member_count, source_count, independent_report_count
            FROM stories WHERE clustering_version_id = {active} ORDER BY id""",
        "locations": f"""SELECT location.story_id, rank, name, country_code, region_code,
            latitude, longitude, source, confidence, supporting_articles, supporting_domains
            FROM story_locations location JOIN stories story ON story.id = location.story_id
            WHERE story.clustering_version_id = {active} ORDER BY location.story_id, rank""",
    }
    metadata = f"""SELECT json_build_object(
        'database', current_database(),
        'latestRun', (SELECT row_to_json(value) FROM (
            SELECT id, run_mode, status, read_article_count, changed_article_count,
                skipped_article_count, failed_article_count, candidate_count, started_at, completed_at
            FROM story_processing_runs WHERE clustering_version_id = {active}
              AND snapshot_id IN (SELECT id FROM story_snapshots
                WHERE snapshot_watermark = '{expected_watermark}'::timestamptz)
            ORDER BY id DESC LIMIT 1
        ) value),
        'latestPublishedWatermark', (SELECT snapshot.snapshot_watermark
            FROM story_publish_commits commit JOIN story_snapshots snapshot ON snapshot.id = commit.snapshot_id
            WHERE commit.clustering_version_id = {active} ORDER BY commit.id DESC LIMIT 1),
        'totals', json_build_object(
            'articles', (SELECT COUNT(*) FROM articles),
            'currentInputs', (SELECT COUNT(*) FROM story_article_inputs WHERE current_marker = 1 AND clustering_version_id = {active}),
            'readyInputs', (SELECT COUNT(*) FROM story_article_inputs WHERE current_marker = 1 AND embedding_status = 'READY' AND clustering_version_id = {active}),
            'stories', (SELECT COUNT(*) FROM stories WHERE clustering_version_id = {active}),
            'memberships', (SELECT COUNT(*) FROM story_memberships WHERE current_marker = 1 AND clustering_version_id = {active}),
            'duplicateMemberships', (SELECT COUNT(*) FROM (SELECT article_ref FROM story_memberships
                WHERE current_marker = 1 AND clustering_version_id = {active} GROUP BY article_ref HAVING COUNT(*) > 1) duplicates),
            'databaseBytes', pg_database_size(current_database())))"""
    sql = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT '__GEM122_metadata__';" + metadata + ";"
    for label, query in queries.items():
        sql += f"SELECT '__GEM122_{label}__'; COPY ({query}) TO STDOUT WITH CSV;"
    sql += "SELECT '__GEM122_end__'; COMMIT;"
    process = subprocess.Popen(["docker", "exec", args.postgres_container, "psql", "-X", "-q", "-A", "-t",
                                "-v", "ON_ERROR_STOP=1", "-U", args.postgres_user, "-d", args.database,
                                "-c", sql], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    hashes = {label: hashlib.sha256() for label in queries}
    sizes = {label: 0 for label in queries}
    result, section = {}, None
    for line in process.stdout:
        if line.startswith(b"__GEM122_") and line.rstrip().endswith(b"__"):
            section = line.decode().strip()[9:-2]
        elif section == "metadata" and line.strip():
            result = json.loads(line)
        elif section in hashes:
            hashes[section].update(line)
            sizes[section] += len(line)
    process.stderr.read()
    if process.wait() != 0 or not result:
        raise RuntimeError("Database evidence export failed")
    result["exports"] = {label: {"sha256": digest.hexdigest(), "bytes": sizes[label]}
                         for label, digest in hashes.items()}
    normalize = lambda value: datetime.fromisoformat(value.replace("Z", "+00:00"))
    result["snapshotMatchesStep"] = bool(result.get("latestPublishedWatermark")) and (
        normalize(result["latestPublishedWatermark"]) == normalize(expected_watermark))
    return result


def run(args):
    if args.database != f"gem122_{args.variant}":
        raise ValueError("Only the matching isolated gem122_before/gem122_after database is allowed")
    if not 0 < args.cpus <= 1:
        raise ValueError("Benchmark CPU allowance must be above zero and at most one core")
    if args.basis_snapshot <= 0:
        raise ValueError("A positive immutable basis snapshot ID is required")
    require_offline_host()
    if host_available_kib() < MIN_HOST_RESERVE_MIB * 1024:
        raise MemoryError("Host MemAvailable is below the 512 MiB safety reserve")
    bench = args.bench_dir.resolve()
    directory = bench / args.variant
    directory.mkdir(exist_ok=True)
    if list(directory.glob("step-*")):
        raise ValueError("Variant directory already has steps; preserve it and use a fresh benchmark directory")
    jar = bench / (args.variant + ".jar")
    probe_script = Path(__file__).with_name("api_probe.py")
    if not jar.is_file() or not probe_script.is_file():
        raise ValueError("Benchmark jar or sibling api_probe.py is missing")
    private_env = (bench / "db.env").read_text()
    private_env, replaced = re.subn(
        r"(?m)^(SPRING_DATASOURCE_URL=jdbc:postgresql://[^\r\n]+/)gem122_seed(\?[^\r\n]*)?$",
        lambda match: match[1] + args.database + (match[2] or ""), private_env)
    if replaced != 1:
        raise ValueError("db.env must contain exactly one datasource URL for gem122_seed")
    env_path = bench / (args.variant + ".env")
    env_path.write_text(private_env)
    env_path.chmod(0o600)
    if args.network is None:
        inspected = docker("inspect", "--format", "{{json .NetworkSettings.Networks}}", args.postgres_container,
                           capture_output=True, text=True)
        networks = json.loads(inspected.stdout)
        if len(networks) != 1:
            raise ValueError("Select --network explicitly when PostgreSQL has multiple networks")
        args.network = next(iter(networks))
    name = "gem122-" + args.variant
    created = False
    probe = None
    probe_output = None
    started = time.monotonic()
    last_stats = -30
    next_step = 0
    outcome = {"variant": args.variant, "database": args.database, "startedAt": utc().isoformat(), "success": False,
               "image": args.image, "limits": {"cpus": args.cpus, "memoryBytes": 1_073_741_824,
                                               "hostReserveMiB": MIN_HOST_RESERVE_MIB}, "port": args.port,
               "basisSnapshot": args.basis_snapshot}

    def check_resources(allow_stats=True):
        nonlocal last_stats
        available = host_available_kib()
        now = time.monotonic()
        if now - started >= 3600:
            raise TimeoutError("Overall benchmark limit of 3600 seconds reached")
        if (allow_stats and now - last_stats >= 30) or available < MIN_HOST_RESERVE_MIB * 1024:
            stats = docker("stats", "--no-stream", "--format", "{{json .}}", name,
                           capture_output=True, text=True)
            sample = {"utc": utc().isoformat(), "memAvailableKiB": available,
                      "container": json.loads(stats.stdout) if stats.stdout.strip() else None}
            state = json.loads(docker("inspect", "--format", "{{json .State}}", name,
                                      capture_output=True, text=True).stdout)
            sample["state"] = {key: state[key] for key in ("Running", "OOMKilled", "ExitCode")}
            with (directory / "resources.jsonl").open("a") as output:
                output.write(json.dumps(sample) + "\n")
            last_stats = now
            if not state["Running"]:
                raise RuntimeError(f"Benchmark container exited: OOMKilled={state['OOMKilled']}, exit={state['ExitCode']}")
        if available < MIN_HOST_RESERVE_MIB * 1024:
            raise MemoryError("Host MemAvailable fell below the 512 MiB safety reserve")

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
        options = ["--spring.docker.compose.enabled=false", "--gdelt.ingestion.enabled=false",
                   "--gdelt.staging.enabled=false", "--gdelt.retention.enabled=false", "--articles.enabled=false",
                   "--stories.embeddings.incremental.enabled=false", "--stories.embeddings.repair.enabled=false",
                   "--stories.snapshots.incremental.enabled=false", "--stories.snapshots.backfill.enabled=false",
                   "--stories.snapshots.retention.enabled=false",
                   "--stories.hnsw-shadow.enabled=false", "--spring.jpa.hibernate.ddl-auto=none",
                   "--stories.visible-version=story-mvp-title-embedding-24h-v1.1.0",
                   "--stories.snapshots.benchmark.watermark=" + INITIAL,
                   "--stories.snapshots.benchmark.sequence-dir=/sequence"]
        docker("run", "--detach", "--name", name, "--cpus", str(args.cpus), "--memory", "1g", "--memory-swap", "1g",
               "--pids-limit", "256", "--network", args.network, "--publish", f"127.0.0.1:{args.port}:8080",
               "--env-file", str(env_path), "--mount", f"type=bind,src={jar},dst=/app/app.jar,readonly",
               "--mount", f"type=bind,src={directory},dst=/sequence", "--entrypoint", "java", args.image,
               "-Xms128m", "-Xmx700m", "-XX:ActiveProcessorCount=1", "-XX:MaxDirectMemorySize=96m",
               "-jar", "/app/app.jar", *options, capture_output=True)
        created = True
        base_url = f"http://127.0.0.1:{args.port}"
        readiness_deadline = time.monotonic() + 120
        while True:
            check_resources()
            try:
                with urllib.request.urlopen(base_url + "/articles?limit=1", timeout=2) as response:
                    if response.status == 200:
                        break
            except Exception:
                pass
            if time.monotonic() >= readiness_deadline:
                raise TimeoutError("API did not become ready within 120 seconds")
            time.sleep(1)
        verified = preflight(args)
        write_json(directory / "preflight.json", verified)
        if not verified["basisSnapshotExists"] or verified["omittedCurrentMembers"]:
            raise RuntimeError("Preflight failed: immutable basis missing or current published members omitted")
        probe_output = (directory / "api-probe.log").open("w")
        probe = subprocess.Popen([sys.executable, str(probe_script), base_url, str(directory / "api.csv"),
                                  "--seconds", "3600", "--interval", "1", "--timeout", "5"],
                                 stdout=probe_output, stderr=subprocess.STDOUT)
        release(0, INITIAL, "SELECT 1;")
        warmup_released = time.monotonic()
        while not read_result(0):
            check_resources()
            if time.monotonic() - warmup_released > 1800:
                raise TimeoutError("Warm-up did not complete within 1800 seconds")
            time.sleep(1)
        write_json(directory / "step-0.database.json", database_evidence(args, INITIAL))
        base_monotonic, base_wall = time.monotonic(), utc()
        schedule = {str(step): (base_wall + timedelta(seconds=(step - 1) * 300)).isoformat() for step in (1, 2, 3)}
        write_json(directory / "schedule.json", schedule)
        released, captured = {}, set()
        while len(captured) < 3:
            now = time.monotonic()
            for step, cutoff in ((1, "08:00:00Z"), (2, "08:05:00Z"), (3, "08:10:00Z")):
                planned = base_monotonic + (step - 1) * 300
                if step not in released and now >= planned:
                    release(step, DAY + cutoff, replay_sql(DAY + cutoff, args.basis_snapshot))
                    released[step] = time.monotonic()
                    with (directory / "releases.jsonl").open("a") as output:
                        output.write(json.dumps({"step": step, "releasedAt": utc().isoformat(),
                                                 "latenessMs": (released[step] - planned) * 1000}) + "\n")
                if step in released and step not in captured:
                    result = read_result(step)
                    if result:
                        write_json(directory / f"step-{step}.database.json",
                                   database_evidence(args, result["watermark"]))
                        captured.add(step)
                    elif now - released[step] > 1800:
                        raise TimeoutError(f"Step {step} did not complete within 1800 seconds")
            if probe.poll() is not None:
                raise RuntimeError("API probe exited before measurement completed")
            future = [base_monotonic + (step - 1) * 300 for step in (1, 2, 3) if step not in released]
            check_resources(allow_stats=not future or min(future) - time.monotonic() > 3)
            time.sleep(min(1, max(0, min(future) - time.monotonic())) if future else 1)
        outcome["success"] = True
    except Exception as exception:
        outcome.update(error=type(exception).__name__, reason=str(exception))
    finally:
        if created:
            (directory / f"step-{next_step}.stop").touch()
        if probe is not None and probe.poll() is None:
            probe.send_signal(signal.SIGINT)
            try:
                probe.wait(timeout=10)
            except subprocess.TimeoutExpired:
                probe.kill()
                probe.wait()
        if probe_output is not None:
            probe_output.close()
        if created:
            with (directory / "engine.log").open("wb") as output:
                subprocess.run(["docker", "logs", name], stdout=output, stderr=subprocess.STDOUT)
            stopped = subprocess.run(["docker", "stop", "--time", "10", name],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if stopped.returncode:
                outcome.update(success=False, cleanupError="Could not stop benchmark container")
        outcome["completedAt"] = utc().isoformat()
        write_json(directory / "runner.result.json", outcome)
        print(json.dumps(outcome))
    return 0 if outcome["success"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("variant", choices=("before", "after"))
    parser.add_argument("--bench-dir", required=True, type=Path)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, default=58082)
    parser.add_argument("--cpus", type=float, default=1.0)
    parser.add_argument("--basis-snapshot", type=int, default=79)
    parser.add_argument("--image", default=IMAGE)
    parser.add_argument("--postgres-container", default="sentinel-gem-vps-postgres-1")
    parser.add_argument("--postgres-user", default="gne")
    parser.add_argument("--network")
    sys.exit(run(parser.parse_args()))


if __name__ == "__main__":
    main()
