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


if __name__ == "__main__":
    unittest.main()
