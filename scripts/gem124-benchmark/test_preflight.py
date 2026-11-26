import unittest

from preflight import MIB, REFERENCE_CPU, acceptance_errors, envelope


class PreflightTests(unittest.TestCase):
    def test_budget_reserves_host_memory_and_rejects_old_four_gib_container_sum(self):
        services = {
            "postgres": {"cpus": 0.75, "mem_limit": 1152 * MIB, "memswap_limit": 1152 * MIB},
            "runner": {"cpus": 1.25, "mem_limit": 2048 * MIB, "memswap_limit": 2048 * MIB},
        }
        budget, errors = envelope(services)
        self.assertFalse(errors)
        self.assertEqual(budget["totalBudgetBytes"], 3712 * MIB)
        services["postgres"]["mem_limit"] = 2048 * MIB
        self.assertTrue(envelope(services)[1])

    def test_local_ryzen_quotas_cannot_establish_acceptance(self):
        host = {"system": "Windows", "cpuModel": "AMD Ryzen 9 9900X",
                "logicalCpus": 24, "memoryBytes": 32 * 1024 * MIB}
        daemon = {"OperatingSystem": "Docker Desktop", "NCPU": 24}
        self.assertTrue(acceptance_errors(host, daemon, True, REFERENCE_CPU))

    def test_matching_host_still_requires_explicit_dedicated_declaration(self):
        host = {"system": "Linux", "cpuModel": REFERENCE_CPU,
                "logicalCpus": 2, "memoryBytes": 3911 * MIB}
        daemon = {"OperatingSystem": "Ubuntu", "NCPU": 2}
        self.assertTrue(acceptance_errors(host, daemon, False, REFERENCE_CPU))
        self.assertFalse(acceptance_errors(host, daemon, True, REFERENCE_CPU))


if __name__ == "__main__":
    unittest.main()
