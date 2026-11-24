"""Summarize downloaded GEM-122 sequence results, phase logs and API samples."""
import argparse
import csv
from datetime import datetime, timedelta
import json
import math
from pathlib import Path
import re


def instant(value):
    value = re.sub(r"(\d{2}:\d{2}:\d{2}\.\d{6})\d+", r"\1", value)
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Evidence timestamps must include a UTC offset")
    return parsed


def api_summary(samples):
    summary = {}
    for endpoint in ("articles", "stories"):
        rows = [row for row in samples if row["endpoint"] == endpoint]
        values = sorted(row["ms"] for row in rows if row["status"] == 200 and not row["error"])
        summary[endpoint] = {
            "requests": len(rows), "failures": len(rows) - len(values),
            "p50Ms": values[math.ceil(len(values) * .50) - 1] if values else None,
            "p95Ms": values[math.ceil(len(values) * .95) - 1] if values else None,
            "maxMs": values[-1] if values else None,
        }
    return summary


def overlapping(samples, start, end):
    return [row for row in samples if row["end"] >= start and row["start"] <= end]


def summarize(directory, first_step):
    results = sorted((json.loads(path.read_text()) for path in directory.glob("step-*.result.json")),
                     key=lambda result: result["step"])
    warnings = []
    phases, marked, pending = [], {}, []
    log = directory / "engine.log"
    for line in log.read_text(errors="replace").splitlines() if log.exists() else []:
        if "Story run timings:" in line and "mode=INCREMENTAL" in line:
            fields = dict(re.findall(r"(snapshot|loadMs|compareMs|publishMs|candidates)=(\d+)", line))
            phase = {key: int(value) for key, value in fields.items()}
            phases.append(phase)
            pending.append(phase)
        if "Story sequence step completed:" in line:
            match = re.search(r"step=(\d+)", line)
            if match:
                marked[int(match[1])] = pending
                pending = []
    matched = {}
    if marked:
        for result in results:
            if result.get("succeeded", 0):
                candidates = marked.get(result["step"], [])
                if len(candidates) == 1:
                    matched[result["step"]] = candidates[0]
                else:
                    warnings.append(f"Step {result['step']}: expected one phase log, found {len(candidates)}")
    else:
        successful = [result for result in results if result.get("succeeded", 0)]
        if len(phases) != len(successful):
            warnings.append(f"Phase logs {len(phases)} differ from successful results {len(successful)}")
        else:
            matched = {result["step"]: phase for result, phase in zip(successful, phases)}

    samples = []
    api_file = directory / "api.csv"
    if api_file.exists():
        with api_file.open(newline="", encoding="utf-8-sig") as source:
            for row in csv.DictReader(source):
                end = instant(row.get("utc") or row["At"])
                ms = float(row.get("milliseconds") or row["Milliseconds"])
                samples.append({"end": end, "start": end - timedelta(milliseconds=ms), "ms": ms,
                                "endpoint": row.get("endpoint") or row["Endpoint"],
                                "status": int(row.get("status") or row["Status"]),
                                "error": row.get("error") or row.get("Error") or ""})
    else:
        warnings.append("Missing api.csv")
    schedule_file = directory / "schedule.json"
    schedule = {int(step): instant(release) for step, release in
                json.loads(schedule_file.read_text()).items()} if schedule_file.exists() else {}
    selected = [result for result in results if result["step"] >= first_step]
    steps = []
    for result in selected:
        step = result["step"]
        start, end = instant(result["startedAt"]), instant(result["completedAt"])
        elapsed = result["elapsedMs"]
        output = {"step": step, "watermark": result["watermark"],
                  "startedAt": result["startedAt"], "completedAt": result["completedAt"],
                  "elapsedMs": elapsed, "processingWithinBudget": elapsed <= 300_000,
                  "published": result.get("succeeded", 0) > 0,
                  "selected": result.get("selected", 0), "succeeded": result.get("succeeded", 0),
                  "reused": result.get("reused", 0), "failed": result.get("failed", 0),
                  "phases": matched.get(step), "api": api_summary(overlapping(samples, start, end))}
        if result.get("error"):
            output["error"] = result["error"]
        if step in schedule:
            lag = (end - schedule[step]).total_seconds() * 1000
            output.update(scheduledAt=schedule[step].isoformat(), completionLagMs=lag,
                          releaseBudgetPassed=output["published"] and 0 <= lag <= 300_000)
        steps.append(output)
    measured_releases = {step: release for step, release in schedule.items() if step >= first_step}
    completions = {result["step"]: instant(result["completedAt"]) for result in selected
                   if result.get("succeeded", 0) > 0}
    backlog = []
    for step, release in sorted(measured_releases.items(), key=lambda item: item[1]):
        unfinished = [other for other, scheduled in measured_releases.items()
                      if scheduled <= release and (other not in completions or completions[other] > release)]
        backlog.append({"step": step, "releasedAt": release.isoformat(),
                        "unfinishedEarlierIntervals": len([other for other in unfinished
                                                          if measured_releases[other] < release]),
                        "pendingIncludingRelease": len(unfinished)})
    window_samples = []
    if selected:
        start = min(instant(result["startedAt"]) for result in selected)
        end = max(instant(result["completedAt"]) for result in selected)
        window_samples = overlapping(samples, start, end)
    return {"variant": directory.name, "firstStep": first_step, "steps": steps,
            "apiMeasuredWindow": api_summary(window_samples), "backlogAtReleases": backlog,
            "warnings": warnings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", type=Path)
    parser.add_argument("--first-step", type=int, default=1, help="Exclude warm-up step 0 by default")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    evidence = {"budgetMs": 300_000,
                "variants": [summarize(directory, args.first_step) for directory in args.directories]}
    rendered = json.dumps(evidence, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
