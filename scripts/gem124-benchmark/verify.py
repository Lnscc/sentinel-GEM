"""Validate local integrated evidence without upgrading it to a VPS acceptance claim."""

import argparse
import json
from pathlib import Path


def verify(manifest, before, after, smoke=False):
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    require(manifest.get("preflight", {}).get("passed") is True, "Resource preflight failed")
    if not smoke:
        require(manifest.get("binariesUnchanged") is True and manifest.get("binaryProvenance"),
                "Measured binary provenance is missing or changed during execution")
    require(len(manifest.get("variants", [])) == 2, "Both computational variants are required")
    for variant in manifest.get("variants", []):
        require(variant.get("exitCode") == 0, f"{variant.get('variant')}: nonzero exit")
        for container in variant.get("containers", []):
            require(not container["state"].get("OOMKilled"), f"{container['name']}: OOM")
            require(container.get("memoryLimit", 0) > 0 and container.get("nanoCpus", 0) > 0,
                    f"{container['name']}: missing enforced limits")
    for label, report in (("reference", before), ("integrated", after)):
        require(report.get("variant") == label, f"{label}: variant identity mismatch")
        require(report.get("workloadCompleted") is True and not report.get("failure"),
                f"{label}: workload did not complete")
        require(report.get("retryIdempotent") is True, f"{label}: retry unverified")
        require(report.get("noopCandidates") == 0, f"{label}: no-op performs comparisons")
        require(report.get("historicalStatisticsExact") is True, f"{label}: historical statistics unverified")
        require(len(report.get("steps", [])) >= 3, f"{label}: fewer than three intervals")
        require(report.get("apiRequests") and all(row.get("status") == 200 and row.get("milliseconds", 6000) < 5000
                for row in report["apiRequests"]), f"{label}: API failure or timeout")
        require(report.get("importBatches") and all(row.get("errors") == 0 and row.get("staged", 0) > 0
                and row.get("articlesCreated", 0) > 0 for row in report["importBatches"]),
                f"{label}: local staging/extraction missing or failed")
        if not smoke:
            require(report.get("scaleEligible") is True, f"{label}: wrong workload scale")
            require(report.get("retentionProgressObserved") is True, f"{label}: no maintenance progress")
            require(report.get("retentionDuringMeasuredWorkObserved") is True,
                    f"{label}: no retention batch overlapped a measured publication")
            require(report.get("locationCoverage") is True and report.get("initialLocationCount", 0) > 0,
                    f"{label}: nonempty location projection missing")
            require(report.get("protectedSnapshotPairs") == 1, f"{label}: protected pair evidence changed")
            require(any(row.get("removedPairs", 0) > 0 for row in report.get("retentionEvents", [])),
                    f"{label}: no obsolete pair cleanup observed")
            events = dict(line.split() for line in report.get("cgroup.memory.events", "").splitlines())
            require(events.get("oom_kill") == "0" and events.get("oom") == "0", f"{label}: cgroup OOM or missing counters")
        for step in report.get("steps", []):
            require(step.get("run", {}).get("status") == "SUCCEEDED", f"{label}: unsuccessful run")
            require(step.get("run", {}).get("changed_article_count") == report.get("changesPerInterval"),
                    f"{label}: changed population mismatch")
            require(step.get("run", {}).get("read_article_count", 0) == report.get("inputs"),
                    f"{label}: logical input population mismatch")
            if label == "integrated":
                require(step.get("budgetPassed") is True, "Integrated publication missed release deadline")
            require(set(step.get("phasesMs", {})) == {"load", "compare", "publish"},
                    f"{label}: phase measurements incomplete")
            require(step.get("loadedDecodedVectors", 0) > 0, f"{label}: no vectors recorded")
            if not smoke:
                require(step.get("run", {}).get("candidate_count", 0) >=
                        int(report.get("changesPerInterval", 0) * (report.get("inputs", 0) - 1) * .9),
                        f"{label}: candidate workload is not dense")
                require(step.get("gcDelta") and step.get("locations", 0) > 0,
                        f"{label}: GC or location measurements missing")
    require(before.get("epoch") == after.get("epoch"), "Frozen epochs differ")
    for report in (before, after):
        require(report.get("epoch") == manifest.get("frozenEpoch", report.get("epoch")),
                "Report epoch does not match the manifest")
    require(len(before.get("steps", [])) == len(after.get("steps", [])), "Interval counts differ")
    for left, right in zip(before.get("steps", []), after.get("steps", [])):
        for key in ("watermark", "snapshotInputHash", "semanticDigest"):
            require(left.get(key) == right.get(key) and left.get(key) is not None,
                    f"Interval {left.get('interval')}: {key} differs")
        require(left.get("run", {}).get("candidate_count") == right.get("run", {}).get("candidate_count"),
                "Exact candidate populations differ")
    return {"localIntegratedPassed": not errors, "smoke": smoke,
            "vpsAcceptancePassed": False,
            "limitations": ["Local Docker hardware differs from the production VPS",
                "Controlled synthetic vectors/groups are not a production replay",
                "Local TSV staging/extraction excludes download and embedding-provider latency"],
            "errors": errors,
            "integratedIntervals": [{"interval": row.get("interval"),
                "totalMs": row.get("elapsedIncludingInputReleaseMs"),
                "scheduledToPublicationMs": row.get("scheduledToPublicationMs"),
                "phasesMs": row.get("phasesMs"), "candidates": row.get("run", {}).get("candidate_count")}
                for row in after.get("steps", [])]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--name", default="dense")
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    def read(suffix):
        return json.loads((args.directory / f"{args.name}-{suffix}.json").read_text(encoding="utf-8"))
    result = verify(read("manifest"), read("reference"), read("integrated"), args.smoke)
    path = args.directory / f"{args.name}-verification.json"
    path.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0 if result["localIntegratedPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
