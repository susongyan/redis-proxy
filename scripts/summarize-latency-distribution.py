#!/usr/bin/env python3
import argparse
import csv
import pathlib
import re


FILE_RE = re.compile(r"latency-distribution-c(\d+)-p(\d+)\.txt")
SECTION_RE = re.compile(r"^======\s+(.+?)\s+======$")
PERCENTILE_RE = re.compile(r"^([0-9]+(?:\.[0-9]+)?)%\s+<=\s+([0-9]+(?:\.[0-9]+)?)\s+milliseconds")
MAX_RE = re.compile(r"^\s*[0-9.]+\s+[0-9.]+\s+[0-9.]+\s+[0-9.]+\s+[0-9.]+\s+([0-9.]+)\s*$")


def summarize_file(path: pathlib.Path) -> list[dict[str, object]]:
    match = FILE_RE.search(path.name)
    clients = match.group(1) if match else ""
    pipeline = match.group(2) if match else ""
    rows = []
    current: dict[str, object] | None = None
    in_distribution = False
    in_summary_values = False
    for line in path.read_text(errors="ignore").splitlines():
        line = line.strip()
        section = SECTION_RE.match(line)
        if section:
            if current:
                rows.append(current)
            current = {
                "clients": clients,
                "pipeline": pipeline,
                "test": section.group(1),
                "p999_latency_ms": "",
                "p999_source_percentile": "",
                "max_latency_ms": "",
            }
            in_distribution = False
            in_summary_values = False
            continue
        if current is None:
            continue
        if line == "Latency by percentile distribution:":
            in_distribution = True
            continue
        if line == "Cumulative distribution of latencies:":
            in_distribution = False
            continue
        if "avg" in line and "p99" in line and "max" in line:
            in_summary_values = True
            continue
        if in_distribution:
            pct = PERCENTILE_RE.match(line)
            if pct and not current["p999_latency_ms"] and float(pct.group(1)) >= 99.9:
                current["p999_source_percentile"] = pct.group(1)
                current["p999_latency_ms"] = pct.group(2)
            continue
        if in_summary_values:
            max_match = MAX_RE.match(line)
            if max_match:
                current["max_latency_ms"] = max_match.group(1)
                in_summary_values = False
    if current:
        rows.append(current)
    return rows


def write_summary(result_dir: pathlib.Path) -> pathlib.Path:
    rows = []
    for path in sorted(result_dir.glob("latency-distribution-c*-p*.txt")):
        rows.extend(summarize_file(path))
    output = result_dir / "latency-summary.csv"
    fields = ["clients", "pipeline", "test", "p999_latency_ms", "p999_source_percentile", "max_latency_ms"]
    with output.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize redis-benchmark latency distribution output.")
    parser.add_argument("result_dir", type=pathlib.Path)
    args = parser.parse_args()
    print(write_summary(args.result_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
