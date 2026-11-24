"""Verify frozen-input equivalence and the after variant's local five-minute budget."""
import argparse
import json
from pathlib import Path
import re

from summarize import instant, summarize


STEPS = (1, 2, 3)


def read(directory, name):
    return json.loads((directory / name).read_text(encoding="utf-8-sig"))


def verify(before, after):
    errors, warnings, variants = [], [], {}

    def require(condition, message):
        if not condition:
            errors.append(message)

    for name, directory in (("before", before), ("after", after)):
        runner = read(directory, "runner.result.json")
        preflight = read(directory, "preflight.json")
        summary = summarize(directory, 1)
        databases = {step: read(directory, f"step-{step}.database.json") for step in STEPS}
        variants[name] = {"runner": runner, "preflight": preflight,
                          "summary": summary, "databases": databases}
        require(runner.get("success") is True and not runner.get("cleanupError"),
                f"{name}: runner did not finish successfully")
        state = runner.get("finalEngineState")
        if state is None:
            warnings.append(f"{name}: final engine exit/OOM state is missing")
        else:
            require(state.get("Running") is False and state.get("OOMKilled") is False
                    and state.get("ExitCode") == 0, f"{name}: engine did not exit cleanly")
        probe_state = runner.get("finalProbeState") or {}
        require(probe_state.get("Running") is False and probe_state.get("OOMKilled") is False
                and probe_state.get("ExitCode") == 0, f"{name}: missing clean final probe state")
        probe_hash = runner.get("probeClassSha256")
        require(isinstance(probe_hash, str) and re.fullmatch(r"[0-9a-f]{64}", probe_hash),
                f"{name}: missing or invalid probe class hash")
        require(preflight.get("basisSnapshotExists") is True
                and preflight.get("basisMembers", 0) > 0
                and preflight.get("omittedCurrentMembers") == 0, f"{name}: invalid initial basis")
        require(not summary["warnings"], f"{name}: incomplete summary: {summary['warnings']}")
        require([step["step"] for step in summary["steps"]] == list(STEPS),
                f"{name}: expected exactly measured steps 1, 2, 3")
        measured = summary["steps"]
        require(all((instant(b["watermark"]) - instant(a["watermark"])).total_seconds() == 300
                    for a, b in zip(measured, measured[1:])),
                f"{name}: input watermarks are not consecutive five-minute boundaries")
        for step in summary["steps"]:
            number = step["step"]
            require(step.get("selected") == 1 and step.get("succeeded") == 1
                    and step.get("failed") == 0 and step.get("reused") == 0
                    and not step.get("error"), f"{name} step {number}: did not publish exactly once")
            phases = step.get("phases") or {}
            require(all(isinstance(phases.get(key), int) and phases[key] >= 0
                        for key in ("loadMs", "compareMs", "publishMs", "candidates")),
                    f"{name} step {number}: missing phase timings/candidate count")
            database = databases.get(number, {})
            require(database.get("snapshotMatchesStep") is True,
                    f"{name} step {number}: current exports do not match the step watermark")
            require(database.get("totals", {}).get("duplicateMemberships") == 0,
                    f"{name} step {number}: duplicate current memberships or missing evidence")
            latest = database.get("latestRun") or {}
            require(latest.get("status") == "SUCCEEDED" and latest.get("run_mode") == "INCREMENTAL"
                    and latest.get("failed_article_count") == 0,
                    f"{name} step {number}: missing successful incremental database run")
            require(latest.get("candidate_count") == phases.get("candidates")
                    and isinstance(latest.get("candidate_count"), int),
                    f"{name} step {number}: log/database candidate counts differ")
            members = database.get("snapshotMembers") or {}
            require(members.get("compacted") is False and members.get("count", 0) > 0
                    and members.get("bytes", 0) > 0,
                    f"{name} step {number}: immutable input rows missing or compacted")
            for label, digest in (("snapshotInputHash", database.get("snapshotInputHash")),
                                  ("snapshotMembers", members.get("sha256")),
                                  *((key, value.get("sha256")) for key, value
                                    in database.get("exports", {}).items())):
                require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest),
                        f"{name} step {number}: invalid {label} hash")
            require(set(database.get("exports", {})) == {"memberships", "stories", "locations"},
                    f"{name} step {number}: incomplete output exports")

    left, right = variants["before"], variants["after"]
    for key in ("engineLimits", "postgresLimits", "probeLimits", "probeClassSha256", "basisSnapshot"):
        require(left["runner"].get(key) is not None
                and left["runner"].get(key) == right["runner"].get(key), f"Variants differ: {key}")
    for name, variant in variants.items():
        for key in ("engineLimits", "postgresLimits", "probeLimits"):
            limits = variant["runner"].get(key) or {}
            require(all(field in limits for field in ("NanoCpus", "Memory", "MemorySwap", "PidsLimit"))
                    and limits.get("NanoCpus", 0) > 0 and limits.get("Memory", 0) > 0,
                    f"{name}: incomplete {key}")
    for key in ("basisSnapshot", "basisMembers", "environment"):
        require(left["preflight"].get(key) is not None
                and left["preflight"].get(key) == right["preflight"].get(key),
                f"Variants differ or lack initial environment evidence: {key}")
    environment = left["preflight"].get("environment") or {}
    require(bool(environment.get("postgresSettings")) and bool(environment.get("clusteringVersion"))
            and bool(environment.get("postgresVersion")), "Missing PostgreSQL/clustering semantics")
    for step in STEPS:
        a, b = left["databases"][step], right["databases"][step]
        for key in ("snapshotInputHash", "exports"):
            require(a.get(key) is not None and a.get(key) == b.get(key), f"Step {step}: {key} differs")
        for key in ("sha256", "bytes", "count", "watermark"):
            require(a.get("snapshotMembers", {}).get(key) is not None
                    and a.get("snapshotMembers", {}).get(key) == b.get("snapshotMembers", {}).get(key),
                    f"Step {step}: immutable input {key} differs")
        require(a.get("latestRun", {}).get("candidate_count") == b.get("latestRun", {}).get("candidate_count"),
                f"Step {step}: exact candidate counts differ")

    performance = {}
    for name, variant in variants.items():
        failures = []
        summary = variant["summary"]
        for step in summary["steps"]:
            if step.get("releaseBudgetPassed") is not True:
                failures.append(f"Step {step['step']}: publication missed its scheduled five-minute budget")
            for endpoint, api in step["api"].items():
                if api["requests"] <= 0 or api["failures"] != 0:
                    failures.append(f"Step {step['step']}: {endpoint} has missing samples or failures")
        backlog = summary["backlogAtReleases"]
        if [row["step"] for row in backlog] != list(STEPS) or any(
                row["unfinishedEarlierIntervals"] != 0 for row in backlog):
            failures.append("Missing release schedule or unfinished earlier intervals")
        if any((instant(b["releasedAt"]) - instant(a["releasedAt"])).total_seconds() != 300
               for a, b in zip(backlog, backlog[1:])):
            failures.append("Releases were not scheduled every 300 seconds")
        for endpoint, api in summary["apiMeasuredWindow"].items():
            if api["requests"] <= 0 or api["failures"] != 0:
                failures.append(f"{endpoint}: measured window has missing samples or HTTP failures")
        performance[name] = {"passed": not failures, "errors": failures, "measurement": summary}
    correctness = {"passed": not errors, "errors": errors}
    return {"accepted": correctness["passed"] and performance["after"]["passed"],
            "scope": "Local constrained Docker replay; not a VPS production measurement",
            "correctness": correctness, "performance": performance, "warnings": warnings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.before, args.after)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = {"accepted": False, "correctness": {"passed": False,
                  "errors": [f"Incomplete or invalid evidence: {error}"]}}
    rendered = json.dumps(result, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0 if result["accepted"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
