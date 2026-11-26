"""Read-only GEM-124 resource inventory; never creates containers or pulls images."""

import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

MIB = 1024 * 1024
OS_RESERVE = 512 * MIB
REFERENCE_MEMORY = 3911 * MIB
REFERENCE_CPU = "Intel Xeon Processor (Cascadelake)"


def command(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=30, check=False)
    if result.returncode:
        # Commands can mention configuration; do not echo interpolated Compose or environments.
        raise RuntimeError(f"{args[0]} {args[1]} failed (exit {result.returncode})")
    return result.stdout.strip()


def local_host():
    if sys.platform == "linux":
        cpu = next((line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines()
                    if line.startswith("model name")), platform.processor())
        memory = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        return {"cpuModel": cpu, "logicalCpus": os.cpu_count(),
                "memoryBytes": int(memory["MemTotal"].split()[0]) * 1024,
                "availableMemoryBytes": int(memory["MemAvailable"].split()[0]) * 1024,
                "kernel": platform.release(), "system": "Linux"}
    if sys.platform == "win32":
        script = ("$c=Get-CimInstance Win32_Processor; $s=Get-CimInstance Win32_ComputerSystem; "
                  "$o=Get-CimInstance Win32_OperatingSystem; "
                  "@{cpuModel=($c.Name -join '; ');logicalCpus=$s.NumberOfLogicalProcessors;"
                  "memoryBytes=$s.TotalPhysicalMemory;availableMemoryBytes=($o.FreePhysicalMemory*1024);"
                  "system='Windows'}|ConvertTo-Json -Compress")
        return json.loads(command("powershell.exe", "-NoProfile", "-Command", script))
    return {"cpuModel": platform.processor(), "system": platform.system(), "logicalCpus": os.cpu_count()}


def envelope(services):
    memory = sum(int(service.get("mem_limit", 0)) for service in services.values())
    cpus = sum(float(service.get("cpus", 0)) for service in services.values())
    errors = []
    if set(services) != {"postgres", "runner"}:
        errors.append("Expected exactly postgres and runner; include probes inside the runner budget")
    for name, service in services.items():
        limit = int(service.get("mem_limit", 0))
        if limit <= 0 or float(service.get("cpus", 0)) <= 0:
            errors.append(f"{name}: explicit CPU and memory limits required")
        if int(service.get("memswap_limit", -1)) != limit:
            errors.append(f"{name}: additional container swap must be disabled")
    if int(services.get("postgres", {}).get("mem_limit", 0)) > 1152 * MIB:
        errors.append("PostgreSQL exceeds 1152 MiB")
    if int(services.get("runner", {}).get("mem_limit", 0)) > 2048 * MIB:
        errors.append("Runner exceeds 2048 MiB")
    if cpus > 2 or memory + OS_RESERVE > REFERENCE_MEMORY:
        errors.append("Combined envelope exceeds 2 CPUs / reference RAM including 512 MiB OS reserve")
    return {"cpuQuotaSum": cpus, "containerMemoryBytes": memory,
            "osReserveBytes": OS_RESERVE, "totalBudgetBytes": memory + OS_RESERVE}, errors


def acceptance_errors(host, daemon, dedicated, reference_cpu):
    errors = []
    if not dedicated:
        errors.append("Acceptance requires --dedicated-host, declaring a separate non-production host")
    if host.get("system") != "Linux" or any(word in str(daemon).lower() for word in ("wsl", "desktop", "microsoft")):
        errors.append("Acceptance requires a native Linux host, not Docker Desktop/WSL")
    if " ".join(host.get("cpuModel", "").split()).casefold() != " ".join(reference_cpu.split()).casefold():
        errors.append("Actual CPU model does not match the declared reference; quotas do not establish equivalence")
    if host.get("logicalCpus") != 2 or daemon.get("NCPU") != 2:
        errors.append("Acceptance requires a whole host with 2 vCPUs, not quotas on a larger host")
    memory = host.get("memoryBytes", 0)
    if not 3712 * MIB <= memory <= 4352 * MIB:
        errors.append("Acceptance requires approximately 4 GiB whole-host RAM including OS reserve")
    return errors


def inspect_environment(args):
    context = json.loads(command("docker", "context", "inspect"))[0]
    endpoint = context["Endpoints"]["docker"]["Host"]
    if not os.environ.get("DOCKER_CONTEXT"):
        endpoint = os.environ.get("DOCKER_HOST") or endpoint
    if not endpoint.startswith(("unix://", "npipe://")):
        raise RuntimeError("Remote Docker endpoints denied; execute preflight directly on the dedicated host")
    daemon = json.loads(command("docker", "info", "--format", "{{json .}}"))
    daemon = {key: daemon.get(key) for key in ("NCPU", "MemTotal", "OperatingSystem", "KernelVersion",
              "OSType", "CgroupVersion", "MemoryLimit", "CpuCfsQuota", "CpuCfsPeriod")}
    compose = json.loads(command("docker", "compose", "-f", str(args.compose), "config", "--format", "json"))
    services = compose["services"]
    budget, errors = envelope(services)
    if compose.get("name") != "gem124-benchmark":
        errors.append("Dedicated Compose project gem124-benchmark required")
    runner_mounts = services.get("runner", {}).get("volumes", [])
    if not any(mount.get("type") == "bind" and mount.get("target") == "/workspace"
               and Path(mount.get("source", "")).is_absolute() for mount in runner_mounts):
        errors.append("An explicit absolute isolated workspace bind is required")
    postgres_ports = services.get("postgres", {}).get("ports", [])
    if len(postgres_ports) != 1 or str(postgres_ports[0].get("published")) != "55424" or postgres_ports[0].get("host_ip") != "127.0.0.1":
        errors.append("PostgreSQL must bind only 127.0.0.1:55424")
    images = {}
    for name, service in services.items():
        images[name] = json.loads(command("docker", "image", "inspect", service["image"],
                                          "--format", "{{json .Id}}"))
    host = local_host()
    if daemon.get("OSType") != "linux" or not all(daemon.get(key) for key in ("MemoryLimit", "CpuCfsQuota", "CpuCfsPeriod")):
        errors.append("Linux Docker with memory and CFS quota support required")
    if daemon.get("NCPU", 0) < 2 or daemon.get("MemTotal", 0) < budget["totalBudgetBytes"]:
        errors.append("Docker daemon resources do not fit the envelope plus reserve")
    if host.get("availableMemoryBytes", 0) < budget["totalBudgetBytes"]:
        errors.append("Insufficient currently available host RAM for the container envelope plus OS reserve")
    running = command("docker", "ps", "-q").split()
    allocations = []
    for container in running:
        # Select only limits, names and project labels; never read container environments.
        template = ('{"name":{{json .Name}},"memory":{{.HostConfig.Memory}},'
                    '"nanoCpus":{{.HostConfig.NanoCpus}},"cpuQuota":{{.HostConfig.CpuQuota}},'
                    '"cpuPeriod":{{.HostConfig.CpuPeriod}},'
                    '"project":{{json (index .Config.Labels "com.docker.compose.project")}}}')
        allocations.append(json.loads(command("docker", "inspect", "--format", template, container)))
    if allocations:
        errors.append("Preflight requires an idle Docker host before starting the benchmark")
    eligibility = acceptance_errors(host, daemon, args.dedicated_host, args.reference_cpu_model)
    if args.mode == "acceptance":
        errors.extend(eligibility)
    return {"mode": args.mode, "passed": not errors,
            "acceptanceEligible": args.mode in ("acceptance", "docker-acceptance") and not errors,
            "dockerAcceptanceEligible": args.mode == "docker-acceptance" and not errors,
            "limitations": (["Docker hardware is documented; identical production-VPS latency is not guaranteed"]
                            if args.mode == "docker-acceptance" else eligibility if args.mode == "local-preflight" else []),
            "host": host, "daemon": daemon, "budget": budget,
            "imageIds": images, "runningContainers": allocations, "errors": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("local-preflight", "acceptance", "docker-acceptance"), required=True)
    parser.add_argument("--compose", type=Path, default=Path(__file__).with_name("compose.yaml"))
    parser.add_argument("--dedicated-host", action="store_true")
    parser.add_argument("--reference-cpu-model", default=REFERENCE_CPU)
    args = parser.parse_args()
    try:
        report = inspect_environment(args)
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        report = {"mode": args.mode, "passed": False, "acceptanceEligible": False, "errors": [str(error)]}
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
