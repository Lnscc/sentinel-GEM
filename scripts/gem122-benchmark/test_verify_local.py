"""Offline acceptance-gate checks: python -m unittest discover -s scripts/gem122-benchmark -p test_verify_local.py."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import verify_local


class VerificationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.before, self.after = (Path(self.temporary.name) / name for name in ("before", "after"))
        self.summaries = {}
        for directory in (self.before, self.after):
            directory.mkdir()
            self.write(directory, "runner.result.json", {
                "success": True, "basisSnapshot": 79,
                "engineLimits": {"NanoCpus": 10**9, "Memory": 2**31, "MemorySwap": 2**31, "PidsLimit": 256},
                "postgresLimits": {"NanoCpus": 10**9, "Memory": 2**31, "MemorySwap": 2**31, "PidsLimit": 0},
                "probeLimits": {"NanoCpus": 250_000_000, "Memory": 2**27, "MemorySwap": 2**27, "PidsLimit": 128},
                "probeClassSha256": "e" * 64,
                "finalProbeState": {"Running": False, "OOMKilled": False, "ExitCode": 0},
                "finalEngineState": {"Running": False, "OOMKilled": False, "ExitCode": 0}})
            self.write(directory, "preflight.json", {
                "basisSnapshotExists": True, "basisMembers": 10, "basisSnapshot": 79,
                "omittedCurrentMembers": 0, "environment": {
                    "postgresSettings": {"work_mem": "4096"}, "postgresVersion": "17",
                    "clusteringVersion": {"version_key": "same-version"}}})
            steps, backlog = [], []
            for step in (1, 2, 3):
                timestamp = (datetime(2026, 11, 24, 8, tzinfo=timezone.utc)
                             + timedelta(minutes=(step - 1) * 5)).isoformat()
                api = {endpoint: {"requests": 2, "failures": 0} for endpoint in ("articles", "stories")}
                steps.append({"step": step, "watermark": timestamp,
                              "selected": 1, "succeeded": 1, "failed": 0, "reused": 0,
                              "phases": {"loadMs": 10, "compareMs": 20, "publishMs": 30, "candidates": 40},
                              "releaseBudgetPassed": True, "api": api})
                backlog.append({"step": step, "releasedAt": timestamp, "unfinishedEarlierIntervals": 0})
                self.write(directory, f"step-{step}.database.json", {
                    "snapshotMatchesStep": True, "totals": {"duplicateMemberships": 0},
                    "latestRun": {"status": "SUCCEEDED", "run_mode": "INCREMENTAL",
                                  "failed_article_count": 0, "candidate_count": 40},
                    "snapshotInputHash": "a" * 64,
                    "snapshotMembers": {"compacted": False, "count": 10, "bytes": 100,
                                        "sha256": "b" * 64, "watermark": timestamp},
                    "exports": {key: {"sha256": "c" * 64, "bytes": 10}
                                for key in ("memberships", "stories", "locations")}})
            self.summaries[directory] = {"warnings": [], "steps": steps,
                                         "backlogAtReleases": backlog, "apiMeasuredWindow": api}
        self.mock = patch.object(verify_local, "summarize", side_effect=lambda directory, _: self.summaries[directory])
        self.mock.start()
        self.addCleanup(self.mock.stop)

    def write(self, directory, name, value):
        (directory / name).write_text(json.dumps(value))

    def verify(self):
        return verify_local.verify(self.before, self.after)

    def test_accepts_equivalent_evidence_with_slow_baseline(self):
        self.summaries[self.before]["steps"][0]["releaseBudgetPassed"] = False
        result = self.verify()
        self.assertTrue(result["accepted"])
        self.assertFalse(result["performance"]["before"]["passed"])

    def test_rejects_mismatched_exports_and_wrong_step_snapshot(self):
        evidence = verify_local.read(self.after, "step-2.database.json")
        evidence["exports"]["stories"]["sha256"] = "d" * 64
        evidence["snapshotMatchesStep"] = False
        self.write(self.after, "step-2.database.json", evidence)
        result = self.verify()
        self.assertFalse(result["accepted"])
        self.assertTrue(any("exports differs" in message for message in result["correctness"]["errors"]))

    def test_rejects_budget_failure_or_missing_http_samples(self):
        self.summaries[self.after]["steps"][1]["releaseBudgetPassed"] = False
        self.summaries[self.after]["steps"][2]["api"]["articles"]["requests"] = 0
        result = self.verify()
        self.assertTrue(result["correctness"]["passed"])
        self.assertFalse(result["accepted"])

    def test_rejects_oom_and_incomplete_steps(self):
        runner = verify_local.read(self.after, "runner.result.json")
        runner["finalEngineState"]["OOMKilled"] = True
        self.write(self.after, "runner.result.json", runner)
        self.summaries[self.after]["steps"].pop()
        self.assertFalse(self.verify()["correctness"]["passed"])

    def test_rejects_probe_parity_changes(self):
        original = verify_local.read(self.after, "runner.result.json")
        for field, value in (("probeClassSha256", "f" * 64), ("probeLimits", {})):
            with self.subTest(field=field):
                self.write(self.after, "runner.result.json", dict(original, **{field: value}))
                self.assertFalse(self.verify()["correctness"]["passed"])

    def test_rejects_missing_or_failed_probe_shutdown(self):
        original = verify_local.read(self.after, "runner.result.json")
        for state in (None, {"Running": False, "OOMKilled": True, "ExitCode": 137}):
            with self.subTest(state=state):
                self.write(self.after, "runner.result.json", dict(original, finalProbeState=state))
                self.assertFalse(self.verify()["correctness"]["passed"])


if __name__ == "__main__":
    unittest.main()
