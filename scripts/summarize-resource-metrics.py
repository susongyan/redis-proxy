#!/usr/bin/env python3
import argparse
import csv
import pathlib
import re
import statistics


GC_PAUSE_RE = re.compile(r"Pause[^\n]*?([0-9]+(?:\.[0-9]+)?)ms")
METRIC_RE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?)$")


def summarize_resource_file(path: pathlib.Path) -> dict[str, object] | None:
    rows = []
    with path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            try:
                rows.append({
                    "cpu": float(row["cpu_percent"]),
                    "rss": float(row["rss_kb"]),
                    "vsz": float(row["vsz_kb"]),
                    "threads": float(row["threads"]),
                })
            except (KeyError, ValueError):
                continue
    if not rows:
        return None
    match = re.search(r"resource-c(\d+)-p(\d+)\.csv", path.name)
    return {
        "case": path.stem.removeprefix("resource-"),
        "clients": match.group(1) if match else "",
        "pipeline": match.group(2) if match else "",
        "samples": len(rows),
        "avg_cpu_percent": statistics.fmean(row["cpu"] for row in rows),
        "max_cpu_percent": max(row["cpu"] for row in rows),
        "avg_rss_kb": statistics.fmean(row["rss"] for row in rows),
        "max_rss_kb": max(row["rss"] for row in rows),
        "avg_vsz_kb": statistics.fmean(row["vsz"] for row in rows),
        "max_vsz_kb": max(row["vsz"] for row in rows),
        "avg_threads": statistics.fmean(row["threads"] for row in rows),
        "max_threads": max(row["threads"] for row in rows),
    }


def summarize_gc_logs(result_dir: pathlib.Path) -> dict[str, float]:
    pauses = []
    for path in list(result_dir.glob("gc-*.log")) + list(result_dir.glob("gc-*.log.*")):
        text = path.read_text(errors="ignore")
        for match in GC_PAUSE_RE.finditer(text):
            pauses.append(float(match.group(1)))
    if not pauses:
        return {"gc_pause_count": 0, "gc_max_pause_ms": 0.0, "gc_total_pause_ms": 0.0}
    return {
        "gc_pause_count": len(pauses),
        "gc_max_pause_ms": max(pauses),
        "gc_total_pause_ms": sum(pauses),
    }


def summarize_metrics_snapshots(result_dir: pathlib.Path) -> dict[str, float]:
    max_go_heap = 0.0
    max_go_goroutines = 0.0
    max_jvm_heap = 0.0
    max_jvm_direct = 0.0
    for path in sorted(result_dir.glob("metrics-after-*.prom")):
        jvm_heap = 0.0
        jvm_direct = 0.0
        for line in path.read_text(errors="ignore").splitlines():
            if not line or line.startswith("#"):
                continue
            match = METRIC_RE.match(line)
            if not match:
                continue
            name, labels, raw_value = match.groups()
            value = float(raw_value)
            labels = labels or ""
            if name == "go_memstats_heap_alloc_bytes":
                max_go_heap = max(max_go_heap, value)
            elif name == "go_goroutines":
                max_go_goroutines = max(max_go_goroutines, value)
            elif name == "jvm_memory_used_bytes" and 'area="heap"' in labels:
                jvm_heap += value
            elif name == "jvm_buffer_memory_used_bytes" and ('id="direct"' in labels or 'pool="direct"' in labels):
                jvm_direct += value
        max_jvm_heap = max(max_jvm_heap, jvm_heap)
        max_jvm_direct = max(max_jvm_direct, jvm_direct)
    return {
        "max_go_heap_bytes": max_go_heap,
        "max_go_goroutines": max_go_goroutines,
        "max_jvm_heap_bytes": max_jvm_heap,
        "max_jvm_direct_bytes": max_jvm_direct,
    }


def write_summary(result_dir: pathlib.Path) -> pathlib.Path:
    rows = []
    gc = summarize_gc_logs(result_dir)
    metrics = summarize_metrics_snapshots(result_dir)
    for path in sorted(result_dir.glob("resource-c*-p*.csv")):
        summary = summarize_resource_file(path)
        if summary is None:
            continue
        summary.update(gc)
        summary.update(metrics)
        rows.append(summary)

    output = result_dir / "resource-summary.csv"
    fields = [
        "case",
        "clients",
        "pipeline",
        "samples",
        "avg_cpu_percent",
        "max_cpu_percent",
        "avg_rss_kb",
        "max_rss_kb",
        "avg_vsz_kb",
        "max_vsz_kb",
        "avg_threads",
        "max_threads",
        "gc_pause_count",
        "gc_max_pause_ms",
        "gc_total_pause_ms",
        "max_go_heap_bytes",
        "max_go_goroutines",
        "max_jvm_heap_bytes",
        "max_jvm_direct_bytes",
    ]
    with output.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize benchmark resource CSV files.")
    parser.add_argument("result_dir", type=pathlib.Path)
    args = parser.parse_args()
    print(write_summary(args.result_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
