#!/usr/bin/env python3
import argparse
import csv
import datetime as dt
import pathlib
import re
import sys


def parse_metadata(path: pathlib.Path) -> dict[str, str]:
    metadata: dict[str, str] = {}
    meta = path / "metadata.txt"
    if not meta.exists():
        return metadata
    for line in meta.read_text().splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        metadata[key.strip()] = value.strip()
    return metadata


def parse_result_dir(path: pathlib.Path) -> tuple[dict[str, str], list[dict[str, object]]]:
    metadata = parse_metadata(path)
    rows: list[dict[str, object]] = []
    for file in sorted(path.glob("redis-benchmark-c*-p*.txt")):
        match = re.search(r"c(\d+)-p(\d+)", file.name)
        if not match:
            continue
        clients = int(match.group(1))
        pipeline = int(match.group(2))
        with file.open(newline="") as handle:
            for row in csv.DictReader(handle):
                rows.append({
                    "clients": clients,
                    "pipeline": pipeline,
                    "test": row["test"],
                    "rps": float(row["rps"]),
                    "p50": float(row["p50_latency_ms"]),
                    "p95": float(row["p95_latency_ms"]),
                    "p99": float(row["p99_latency_ms"]),
                })
    return metadata, rows


def parse_resource_summary(path: pathlib.Path) -> dict[str, str]:
    summary = path / "resource-summary.csv"
    if not summary.exists():
        return {}
    rows = []
    with summary.open(newline="") as handle:
        for row in csv.DictReader(handle):
            rows.append(row)
    if not rows:
        return {}

    def nums(field: str) -> list[float]:
        values = []
        for row in rows:
            try:
                values.append(float(row.get(field, "") or 0))
            except ValueError:
                pass
        return values

    avg_cpu = nums("avg_cpu_percent")
    max_rss = nums("max_rss_kb")
    max_threads = nums("max_threads")
    gc_max_pause = nums("gc_max_pause_ms")
    return {
        "avg_cpu_percent": f"{(sum(avg_cpu) / len(avg_cpu)):.2f}" if avg_cpu else "n/a",
        "max_rss_mb": f"{(max(max_rss) / 1024):.2f}" if max_rss else "n/a",
        "max_threads": f"{max(max_threads):.0f}" if max_threads else "n/a",
        "gc_max_pause_ms": f"{max(gc_max_pause):.3f}" if gc_max_pause else "n/a",
    }


def default_run_name(path: pathlib.Path, metadata: dict[str, str]) -> str:
    return metadata.get("run_name") or path.name


def summarize(rows: list[dict[str, object]]) -> dict[str, float]:
    if not rows:
        return {"avg_rps": 0.0, "avg_p99": 0.0, "best_rps": 0.0, "worst_p99": 0.0}
    return {
        "avg_rps": sum(float(row["rps"]) for row in rows) / len(rows),
        "avg_p99": sum(float(row["p99"]) for row in rows) / len(rows),
        "best_rps": max(float(row["rps"]) for row in rows),
        "worst_p99": max(float(row["p99"]) for row in rows),
    }


def winner(values: dict[str, dict[str, object]], metric: str, highest: bool) -> str:
    if highest:
        return max(values, key=lambda name: float(values[name][metric]))
    return min(values, key=lambda name: float(values[name][metric]))


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a markdown report from redis-benchmark result directories.")
    parser.add_argument("result_dirs", nargs="+", type=pathlib.Path)
    parser.add_argument("--title", default="Redis Proxy Benchmark Report")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()

    runs: list[tuple[pathlib.Path, dict[str, str], list[dict[str, object]], dict[str, str]]] = []
    for path in args.result_dirs:
        if not path.exists():
            print(f"missing result dir: {path}", file=sys.stderr)
            return 2
        metadata, rows = parse_result_dir(path)
        if not rows:
            print(f"no redis-benchmark csv files found in: {path}", file=sys.stderr)
            return 2
        runs.append((path, metadata, rows, parse_resource_summary(path)))

    output = args.output
    if output is None:
        stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        output = pathlib.Path("bench-results") / f"comparison-{stamp}.md"
    output.parent.mkdir(parents=True, exist_ok=True)

    key_set = set()
    lookup: dict[str, dict[tuple[int, int, str], dict[str, object]]] = {}
    summaries: list[tuple[str, pathlib.Path, dict[str, str], dict[str, float], dict[str, str]]] = []
    for path, metadata, rows, resources in runs:
        name = default_run_name(path, metadata)
        lookup[name] = {}
        for row in rows:
            key = (int(row["clients"]), int(row["pipeline"]), str(row["test"]))
            lookup[name][key] = row
            key_set.add(key)
        summaries.append((name, path, metadata, summarize(rows), resources))

    with output.open("w") as report:
        report.write(f"# {args.title}\n\n")
        report.write(f"Generated at: {dt.datetime.now().isoformat(timespec='seconds')}\n\n")
        report.write("## Aggregate Results\n\n")
        report.write("| Group | Run | Backend model | Dataplane | Avg RPS | Avg p99 ms | Best RPS | Worst p99 ms | Avg CPU % | Max RSS MB | Max Threads | GC Max Pause ms |\n")
        report.write("|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n")
        for name, _path, metadata, summary, resources in summaries:
            report.write(
                f"| {metadata.get('run_group', 'unspecified')} "
                f"| {name} "
                f"| {metadata.get('backend_model', 'unspecified')} "
                f"| {metadata.get('dataplane', 'unspecified')} "
                f"| {summary['avg_rps']:.2f} "
                f"| {summary['avg_p99']:.2f} "
                f"| {summary['best_rps']:.2f} "
                f"| {summary['worst_p99']:.2f} "
                f"| {resources.get('avg_cpu_percent', 'n/a')} "
                f"| {resources.get('max_rss_mb', 'n/a')} "
                f"| {resources.get('max_threads', 'n/a')} "
                f"| {resources.get('gc_max_pause_ms', 'n/a')} |\n"
            )

        report.write("\n## Scenario Comparison\n\n")
        run_names = [name for name, _path, _metadata, _summary, _resources in summaries]
        rps_headers = " | ".join(f"{name} RPS" for name in run_names)
        p99_headers = " | ".join(f"{name} p99" for name in run_names)
        report.write(f"| Clients | Pipeline | Test | {rps_headers} | {p99_headers} | Throughput Winner | p99 Winner |\n")
        report.write(f"|---:|---:|---|{'---:|' * len(run_names)}{'---:|' * len(run_names)}---|---|\n")
        for key in sorted(key_set):
            values = {name: lookup[name][key] for name in run_names if key in lookup[name]}
            if len(values) != len(run_names):
                continue
            clients, pipeline, test = key
            rps_values = " | ".join(f"{float(values[name]['rps']):.2f}" for name in run_names)
            p99_values = " | ".join(f"{float(values[name]['p99']):.3f}" for name in run_names)
            report.write(
                f"| {clients} | {pipeline} | {test} | {rps_values} | {p99_values} "
                f"| {winner(values, 'rps', True)} | {winner(values, 'p99', False)} |\n"
            )

        report.write("\n## Source Results\n\n")
        for name, path, _metadata, _summary, _resources in summaries:
            report.write(f"- {name}: `{path}`\n")

    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
