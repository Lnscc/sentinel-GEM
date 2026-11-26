"""Validate measured load evidence against the declared local or approved Docker scope."""

import argparse
import json
from pathlib import Path


def verify(manifest, before, after, smoke=False):
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    require(manifest.get("preflight", {}).get("passed") is True, "Resource preflight failed")
    docker_acceptance = manifest.get("preflight", {}).get("mode") == "docker-acceptance"
    if docker_acceptance:
        require(not smoke, "Smoke cannot qualify Docker load acceptance")
        require(manifest["preflight"].get("dockerAcceptanceEligible") is True, "Docker qualification failed")
        parameters = manifest.get("parameters", {})
        require(parameters.get("inputs", 0) >= 107000 and parameters.get("changes", 0) >= 1000,
                "Docker acceptance requires at least 107000 inputs and 1000 changes per release")
        require(parameters.get("intervals", 0) >= 3, "At least three releases are required")
        require(parameters.get("interval_seconds") == 300, "Docker acceptance requires real five-minute releases")
        require(parameters.get("history_inputs", 0) > 0, "Independent historical inputs are required")
        budget = manifest["preflight"].get("budget", {})
        require(0 < budget.get("cpuQuotaSum", 0) <= 2, "Docker CPU budget invalid")
        require(0 < budget.get("containerMemoryBytes", 0) <= 3200 * 1024**2
                and budget.get("osReserveBytes") == 512 * 1024**2, "Docker memory/reserve budget invalid")
    if not smoke:
        require(manifest.get("binariesUnchanged") is True and manifest.get("binaryProvenance"),
                "Measured binary provenance is missing or changed during execution")
    require(len(manifest.get("variants", [])) == 2, "Both computational variants are required")
    for variant in manifest.get("variants", []):
        require(variant.get("exitCode") == 0, f"{variant.get('variant')}: nonzero exit")
        if docker_acceptance:
            containers = variant.get("containers", [])
            require(len(containers) == 2, "Both enforced container envelopes are required")
            require(0 < sum(c.get("nanoCpus", 0) for c in containers) <= 2_000_000_000,
                    "Actual combined CPU quota exceeds budget")
            require(0 < sum(c.get("memoryLimit", 0) for c in containers) <= 3200 * 1024**2,
                    "Actual combined memory exceeds budget")
            require(all(c.get("memorySwap") == c.get("memoryLimit") for c in containers),
                    "Additional container swap is not disabled")
        for container in variant.get("containers", []):
            require(not container["state"].get("OOMKilled"), f"{container['name']}: OOM")
            require(container.get("memoryLimit", 0) > 0 and container.get("nanoCpus", 0) > 0,
                    f"{container['name']}: missing enforced limits")
    for label, report in (("reference", before), ("integrated", after)):
        if docker_acceptance:
            require(report.get("independentNoncurrentHistoryInputs") == manifest.get("parameters", {}).get("history_inputs"),
                    f"{label}: independent history does not match the manifest")
            for report_key, parameter in (("inputs", "inputs"), ("changesPerInterval", "changes"),
                                          ("intervalSeconds", "interval_seconds")):
                require(report.get(report_key) == manifest.get("parameters", {}).get(parameter),
                        f"{label}: {report_key} does not match the manifest")
            require(report.get("inputs", 0) >= 107000 and report.get("changesPerInterval", 0) >= 1000
                    and report.get("intervalSeconds") == 300, f"{label}: numerical workload scale invalid")
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
                if docker_acceptance:
                    require(0 <= step.get("scheduledToPublicationMs", -1) < 300_000,
                            "Integrated publication did not finish within 300 seconds of release")
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
            "dockerLoadAcceptancePassed": docker_acceptance and not smoke and not errors,
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
