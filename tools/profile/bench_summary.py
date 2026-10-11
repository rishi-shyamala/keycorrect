"""Summarise androidx.benchmark JSON results (one line per benchmark).

Usage: python3 -I tools/profile/bench_summary.py <benchmarkData.json>...
"""
import json
import sys


def main(paths):
    for path in paths:
        with open(path) as f:
            data = json.load(f)
        build = data.get("context", {}).get("build", {})
        print(f"device: {build.get('model', '?')} ({build.get('device', '?')}), API {build.get('version', {}).get('sdk', '?')}")
        for b in data.get("benchmarks", []):
            metrics = b.get("metrics", {})
            t = metrics.get("timeNs", {})
            allocs = metrics.get("allocationCount", {}).get("median", 0)
            name = b["className"].split(".")[-1] + "." + b["name"]
            print(
                f"  {name:<40} median {t.get('median', 0) / 1e6:9.3f} ms"
                f"  min {t.get('minimum', 0) / 1e6:9.3f} ms"
                f"  max {t.get('maximum', 0) / 1e6:9.3f} ms"
                f"  allocs {allocs:>9.0f}"
            )


if __name__ == "__main__":
    main(sys.argv[1:])
