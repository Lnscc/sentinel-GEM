"""Poll both read APIs independently while the isolated story worker publishes."""
import argparse
import concurrent.futures
import csv
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import threading
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url", help="Engine root URL, or monitor URL ending in /api")
    parser.add_argument("output", type=Path, help="Request-level CSV; summary uses the same stem")
    parser.add_argument("--seconds", type=float, default=1800)
    parser.add_argument("--interval", type=float, default=1)
    parser.add_argument("--timeout", type=float, default=5)
    args = parser.parse_args()
    if min(args.seconds, args.interval, args.timeout) <= 0:
        parser.error("seconds, interval and timeout must be positive")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    stop = threading.Event()
    lock = threading.Lock()
    deadline = time.monotonic() + args.seconds
    results = {}
    with args.output.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(("utc", "endpoint", "status", "milliseconds", "bytes", "error"))

        def poll(endpoint, query):
            durations = []
            requests = failures = 0
            while not stop.is_set() and time.monotonic() < deadline:
                started = time.monotonic()
                status, size, error = 0, 0, ""
                try:
                    with urllib.request.urlopen(args.base_url.rstrip("/") + "/" + endpoint + query,
                                                timeout=args.timeout) as response:
                        status = response.status
                        size = len(response.read())
                except urllib.error.HTTPError as exc:
                    status, error = exc.code, str(exc)
                except Exception as exc:
                    error = f"{type(exc).__name__}: {exc}"
                elapsed = (time.monotonic() - started) * 1000
                requests += 1
                if status == 200 and not error:
                    durations.append(elapsed)
                else:
                    failures += 1
                with lock:
                    writer.writerow((datetime.now(timezone.utc).isoformat(), endpoint,
                                     status, elapsed, size, error))
                    output.flush()
                stop.wait(min(max(0, args.interval - elapsed / 1000),
                              max(0, deadline - time.monotonic())))
            durations.sort()
            results[endpoint] = {
                "requests": requests, "failures": failures,
                "p50_ms": durations[math.ceil(len(durations) * .50) - 1] if durations else None,
                "p95_ms": durations[math.ceil(len(durations) * .95) - 1] if durations else None,
                "max_ms": durations[-1] if durations else None,
            }

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            tasks = [executor.submit(poll, "articles", "?offset=0&limit=20"),
                     executor.submit(poll, "stories", "?offset=0&limit=20&sort=time&direction=desc")]
            try:
                for task in tasks:
                    task.result()
            except KeyboardInterrupt:
                stop.set()
                for task in tasks:
                    task.result()
    summary = json.dumps(results, indent=2)
    args.output.with_suffix(".summary.json").write_text(summary + "\n", encoding="utf-8")
    print(summary)


if __name__ == "__main__":
    main()
