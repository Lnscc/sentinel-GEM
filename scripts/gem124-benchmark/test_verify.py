import copy
import unittest

from verify import verify


class EvidenceGatesTests(unittest.TestCase):
    def setUp(self):
        self.manifest = {"preflight": {"passed": True}, "variants": [
            {"variant": "reference", "exitCode": 0}, {"variant": "integrated", "exitCode": 0}]}
        report = {"variant": "reference", "workloadCompleted": True, "retryIdempotent": True,
            "noopCandidates": 0, "historicalStatisticsExact": True, "retentionProgressObserved": True,
            "apiRequests": [{"status": 200, "milliseconds": 10}],
            "importBatches": [{"errors": 0, "staged": 10, "articlesCreated": 10}],
            "epoch": "frozen", "changesPerInterval": 10, "inputs": 1070,
            "steps": [{"interval": i, "watermark": str(i), "snapshotInputHash": "frozen-inputs",
                "semanticDigest": "exact-result", "budgetPassed": True,
                "run": {"status": "SUCCEEDED", "changed_article_count": 10,
                        "read_article_count": 1070, "candidate_count": 10690},
                "phasesMs": {"load": 1, "compare": 2, "publish": 1}, "loadedDecodedVectors": 1070}
                for i in range(1, 4)]}
        self.before = report
        self.after = copy.deepcopy(report)
        self.after["variant"] = "integrated"

    def test_successful_local_smoke_does_not_claim_vps_acceptance(self):
        result = verify(self.manifest, self.before, self.after, smoke=True)
        self.assertTrue(result["localIntegratedPassed"], result["errors"])
        self.assertFalse(result["vpsAcceptancePassed"])

    def test_exit_zero_cannot_hide_report_failure(self):
        self.after.update(workloadCompleted=False, failure="AssertionFailedError")
        self.assertFalse(verify(self.manifest, self.before, self.after, True)["localIntegratedPassed"])

    def test_semantic_difference_and_missed_deadline_are_rejected(self):
        self.after["steps"][1].update(semanticDigest="different", budgetPassed=False)
        result = verify(self.manifest, self.before, self.after, True)
        self.assertFalse(result["localIntegratedPassed"])
        self.assertTrue(any("semanticDigest" in error for error in result["errors"]))

    def test_small_fixture_cannot_satisfy_scale_gate(self):
        self.assertFalse(verify(self.manifest, self.before, self.after)["localIntegratedPassed"])

    def docker_fixture(self):
        self.manifest.update(binariesUnchanged=True, binaryProvenance={"classes": "frozen"},
            parameters={"interval_seconds": 300, "history_inputs": 20000, "inputs": 107000,
                        "changes": 1000, "intervals": 3})
        self.manifest["preflight"].update(mode="docker-acceptance", dockerAcceptanceEligible=True,
            budget={"cpuQuotaSum": 2, "containerMemoryBytes": 3200 * 1024**2, "osReserveBytes": 512 * 1024**2})
        for variant in self.manifest["variants"]:
            variant["containers"] = [{"name": name, "state": {"OOMKilled": False},
                "memoryLimit": memory * 1024**2, "memorySwap": memory * 1024**2, "nanoCpus": cpus}
                for name, memory, cpus in (("postgres", 1152, 750_000_000), ("runner", 2048, 1_250_000_000))]
        for report in (self.before, self.after):
            report.update(inputs=107000, changesPerInterval=1000, intervalSeconds=300, scaleEligible=True,
                independentNoncurrentHistoryInputs=20000, retentionDuringMeasuredWorkObserved=True,
                locationCoverage=True, initialLocationCount=2, protectedSnapshotPairs=1,
                retentionEvents=[{"removedPairs": 1}])
            report["cgroup.memory.events"] = "oom 0\noom_kill 0"
            for step in report["steps"]:
                step.update(scheduledToPublicationMs=120000, gcDelta={"collections": 0}, locations=2)
                step["run"].update(changed_article_count=1000, read_article_count=107000,
                    candidate_count=106999000)

    def test_approved_docker_scope_can_pass_without_claiming_vps_equivalence(self):
        self.docker_fixture()
        result = verify(self.manifest, self.before, self.after)
        self.assertTrue(result["dockerLoadAcceptancePassed"], result["errors"])
        self.assertFalse(result["vpsAcceptancePassed"])

    def test_docker_rejects_missing_history_slow_publication_and_excess_resources(self):
        self.docker_fixture()
        for problem in ("history", "deadline", "memory", "swap", "interval", "false_scale", "mismatch"):
            manifest, after = copy.deepcopy(self.manifest), copy.deepcopy(self.after)
            if problem == "history":
                manifest["parameters"]["history_inputs"] = 0
            elif problem == "deadline":
                after["steps"][0]["scheduledToPublicationMs"] = 300001
            elif problem == "memory":
                manifest["variants"][0]["containers"][1]["memoryLimit"] = 4096 * 1024**2
            elif problem == "swap":
                manifest["variants"][0]["containers"][1]["memorySwap"] += 1024
            elif problem == "false_scale":
                after.update(inputs=1070, changesPerInterval=10, scaleEligible=True)
            elif problem == "mismatch":
                after["changesPerInterval"] = 1001
            else:
                manifest["parameters"]["interval_seconds"] = 600
            with self.subTest(problem=problem):
                self.assertFalse(verify(manifest, self.before, after)["dockerLoadAcceptancePassed"])


if __name__ == "__main__":
    unittest.main()
