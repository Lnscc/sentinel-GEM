"""Run precompiled GEM-124 integration fixtures in the isolated Docker envelope."""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / "engine"
OUTPUT = ENGINE / "target/gem124-benchmark"
COMPOSE = Path(__file__).with_name("compose.yaml")
BASELINE = "5ff162133cbf27f07d70a16a8848533357e84b11"
PACKAGE = "com.example.globalnewsenginev1.stories.snapshot"


def run(args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def capture(args, **kwargs):
    return run(args, capture_output=True, **kwargs).stdout.strip()


def compose(env, *args):
    return ["docker", "compose", "-f", str(COMPOSE), *args]


def prepare_classes():
    """Copy cached dependencies and compile only the frozen scalar reference overlay."""
    library = OUTPUT / "lib"
    classpath_file = OUTPUT / "classpath.txt"
    if not classpath_file.exists():
        raise RuntimeError("Compile test classes and run dependency:build-classpath first; see README")
    # Only these generated directories are replaced; evidence files remain intact.
    for name in ("lib", "reference-src", "reference-classes"):
        directory = OUTPUT / name
        if directory.exists():
            if directory.is_symlink() or directory.resolve().parent != OUTPUT.resolve():
                raise RuntimeError(f"Unexpected generated directory: {directory}")
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
    for entry in classpath_file.read_text(encoding="utf-8").strip().split(os.pathsep):
        if entry.endswith(".jar"):
            shutil.copy2(entry, library / Path(entry).name)
    source = OUTPUT / "reference-src"
    classes = OUTPUT / "reference-classes"
    source.mkdir(exist_ok=True)
    classes.mkdir(exist_ok=True)
    for name in ("ExactCosine", "StoryIncrementalPairSearch"):
        text = capture(["git", "show", f"{BASELINE}:engine/src/main/java/"
                        f"{PACKAGE.replace('.', '/')}/{name}.java"], cwd=ROOT)
        (source / f"{name}.java").write_text(text, encoding="utf-8")
    run(["javac", "--release", "21", "-proc:none", "-cp", str(ENGINE / "target/classes"),
         "-d", str(classes), *map(str, source.glob("*.java"))], cwd=ROOT)


def binary_provenance():
    result = {}
    for name in ("classes", "test-classes", "gem124-benchmark/reference-classes", "gem124-benchmark/lib"):
        directory = ENGINE / "target" / name
        digest = hashlib.sha256()
        files = [p for p in sorted(directory.rglob("*")) if p.is_file()]
        for path in files:
            digest.update(path.relative_to(directory).as_posix().encode())
            digest.update(hashlib.sha256(path.read_bytes()).digest())
        result[name] = {"files": len(files), "sha256": digest.hexdigest()}
    for path in [ROOT / "engine/src/test/java" / PACKAGE.replace(".", "/") / "StoryJointAcceptancePostgresIT.java",
                 *Path(__file__).parent.glob("*.py"), COMPOSE]:
        result[path.relative_to(ROOT).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def java_command(variant, args, epoch):
    path = "/workspace/engine/target/"
    classpath = f"{path}test-classes:{path}classes:{path}gem124-benchmark/lib/*"
    if variant == "reference":
        classpath = f"{path}gem124-benchmark/reference-classes:" + classpath
    props = {
        "it.joint.enabled": "true", "it.joint.variant": variant,
        "it.joint.inputs": args.inputs, "it.joint.changes": args.changes,
        "it.joint.history-inputs": args.history_inputs,
        "it.joint.intervals": args.intervals, "it.joint.interval-seconds": args.interval_seconds,
        "it.joint.epoch": epoch,
        "it.joint.retention.poll-seconds": 2 if args.interval_seconds < 300 else 30,
        "it.joint.source": capture(["git", "rev-parse", "HEAD"], cwd=ROOT),
        "spring.devtools.restart.enabled": "false",
        "spring.devtools.livereload.enabled": "false",
        "it.joint.output": f"target/gem124-benchmark/{args.name}-{variant}.json",
        "it.postgres.jdbc-url": "jdbc:postgresql://postgres:5432/gem124",
        "it.postgres.username": "gem124",
        "it.postgres.password": "gem124-disposable-local-test",
    }
    # Every value is controlled here or validated numeric CLI input; no user shell fragments.
    return "java " + " ".join(f"-D{key}={value}" for key, value in props.items()) + \
        f" -cp '{classpath}' {PACKAGE}.StoryJointAcceptancePostgresIT"


def container_snapshot(env):
    ids = capture(compose(env, "ps", "-a", "-q"), env=env).splitlines()
    if not ids:
        return []
    records = json.loads(capture(["docker", "inspect", *ids], env=env))
    return [{"name": r["Name"], "image": r["Image"], "state": r["State"],
             "memoryLimit": r["HostConfig"]["Memory"],
             "memorySwap": r["HostConfig"]["MemorySwap"],
             "nanoCpus": r["HostConfig"]["NanoCpus"]} for r in records]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", default="joint")
    parser.add_argument("--inputs", type=int, default=107000)
    parser.add_argument("--changes", type=int, default=1000)
    parser.add_argument("--history-inputs", type=int, default=0)
    parser.add_argument("--intervals", type=int, default=3)
    parser.add_argument("--interval-seconds", type=int, default=300)
    parser.add_argument("--image", default="sentinel-gem-engine:gem116-check")
    parser.add_argument("--integrated-only", action="store_true")
    parser.add_argument("--docker-acceptance", action="store_true",
                        help="Use the explicitly approved GEM-124 Docker resource qualification")
    args = parser.parse_args()
    if not args.name.replace("-", "").isalnum() or min(args.inputs, args.changes, args.intervals, args.interval_seconds) < 1:
        parser.error("Use an alphanumeric name and positive workload dimensions")
    if args.changes >= args.inputs:
        parser.error("Changes must be smaller than the initial input population")
    if args.history_inputs < 0:
        parser.error("History inputs must be nonnegative")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if any(OUTPUT.glob(f"{args.name}-*")):
        parser.error("Run name already has evidence files; use a fresh --name instead of overwriting evidence")
    prepare_classes()
    started_utc = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    epoch = (started_utc - dt.timedelta(hours=2)).isoformat().replace("+00:00", "Z")
    env = dict(os.environ, GEM124_WORKSPACE=str(ROOT), GEM124_RUNNER_IMAGE=args.image,
               GEM124_COMMAND=java_command("integrated", args, epoch))
    preflight = run(["python", str(Path(__file__).with_name("preflight.py")),
                     "--mode", "docker-acceptance" if args.docker_acceptance else "local-preflight"],
                    env=env, capture_output=True)
    inventory = json.loads(preflight.stdout)
    manifest = {"startedUtc": started_utc.isoformat(), "frozenEpoch": epoch,
                "sourceCommit": capture(["git", "rev-parse", "HEAD"], cwd=ROOT),
                "baselineScalarCommit": BASELINE,
                "scope": "GEM-124 Docker load acceptance" if args.docker_acceptance else "local Docker integrated load test",
                "vpsAcceptancePassed": False, "preflight": inventory,
                "binaryProvenance": binary_provenance(),
                "parameters": vars(args), "variants": []}
    manifest_path = OUTPUT / f"{args.name}-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    try:
        run(compose(env, "up", "-d", "--wait", "postgres"), env=env)
        variants = ("integrated",) if args.integrated_only else ("reference", "integrated")
        for variant in variants:
            env["GEM124_COMMAND"] = java_command(variant, args, epoch)
            log = OUTPUT / f"{args.name}-{variant}.log"
            started = time.monotonic()
            with log.open("w", encoding="utf-8") as out:
                result = subprocess.run(compose(env, "up", "--no-deps", "--force-recreate",
                                               "--exit-code-from", "runner", "runner"),
                                        env=env, stdout=out, stderr=subprocess.STDOUT,
                                        timeout=max(1800, args.intervals * args.interval_seconds * 3))
            manifest["variants"].append({"variant": variant, "exitCode": result.returncode,
                "elapsedSeconds": round(time.monotonic() - started, 3),
                "containers": container_snapshot(env),
                "logSha256": hashlib.sha256(log.read_bytes()).hexdigest()})
            manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
            print(f"{variant}: exit={result.returncode}; log={log}", flush=True)
            if result.returncode:
                raise RuntimeError(f"{variant} failed; inspect its bounded log and JSON report")
            report_path = OUTPUT / f"{args.name}-{variant}.json"
            if not report_path.exists():
                raise RuntimeError(f"{variant} produced no completed report")
            report = json.loads(report_path.read_text(encoding="utf-8"))
            if report.get("epoch") != epoch or report.get("variant") != variant or report.get("inputs") != args.inputs:
                raise RuntimeError(f"{variant} report does not belong to this run")
            if not report.get("workloadCompleted") or report.get("failure"):
                raise RuntimeError(f"{variant} failed its report gate: {report.get('failure')}")
    finally:
        # Preserve the named volume for inspection; never remove existing databases or volumes.
        run(compose(env, "stop"), env=env)
        manifest["completedUtc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        manifest["binariesUnchanged"] = binary_provenance() == manifest["binaryProvenance"]
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Manifest: {manifest_path}")


if __name__ == "__main__":
    main()
