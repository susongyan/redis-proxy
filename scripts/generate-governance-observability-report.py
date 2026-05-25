#!/usr/bin/env python3
import argparse
import datetime as dt
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request


METRIC_RE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+([-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?\d+)?)$")
TOKEN_KEYS = {"token", "tokens", "secret", "password"}


def fetch_text(url: str, timeout: float = 3.0) -> tuple[int, str]:
    request = urllib.request.Request(url, headers={"User-Agent": "redis-proxy-report/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")


def fetch_json(url: str) -> tuple[int, object]:
    status, text = fetch_text(url)
    if status >= 400 or not text.strip():
        return status, {}
    return status, json.loads(text)


def split_labels(raw: str) -> dict[str, str]:
    labels: dict[str, str] = {}
    if not raw:
        return labels
    parts = []
    buf = []
    in_quote = False
    escaped = False
    for char in raw:
        if escaped:
            buf.append(char)
            escaped = False
            continue
        if char == "\\":
            buf.append(char)
            escaped = True
            continue
        if char == '"':
            in_quote = not in_quote
            buf.append(char)
            continue
        if char == "," and not in_quote:
            parts.append("".join(buf))
            buf = []
            continue
        buf.append(char)
    if buf:
        parts.append("".join(buf))
    for part in parts:
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        labels[key.strip()] = bytes(value.strip().strip('"'), "utf-8").decode("unicode_escape")
    return labels


def parse_metrics(text: str) -> list[dict[str, object]]:
    samples = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = METRIC_RE.match(line)
        if not match:
            continue
        name, _labels_part, raw_labels, raw_value = match.groups()
        samples.append({"name": name, "labels": split_labels(raw_labels or ""), "value": float(raw_value)})
    return samples


def select(samples: list[dict[str, object]], name: str) -> list[dict[str, object]]:
    return [sample for sample in samples if sample["name"] == name]


def total(samples: list[dict[str, object]], name: str) -> float:
    return sum(float(sample["value"]) for sample in select(samples, name))


def gauge(samples: list[dict[str, object]], name: str) -> float | None:
    values = select(samples, name)
    if not values:
        return None
    return float(values[-1]["value"])


def rows(samples: list[dict[str, object]], name: str, limit: int = 20) -> list[dict[str, object]]:
    items = sorted(select(samples, name), key=lambda item: float(item["value"]), reverse=True)
    return items[:limit]


def clean(value: object) -> object:
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if str(key).lower() in TOKEN_KEYS:
                continue
            result[key] = clean(item)
        return result
    if isinstance(value, list):
        return [clean(item) for item in value]
    return value


def table(headers: list[str], data: list[list[object]]) -> str:
    if not data:
        return "_无数据_\n"
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for row in data:
        lines.append("| " + " | ".join(format_cell(value) for value in row) + " |")
    return "\n".join(lines) + "\n"


def format_cell(value: object) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        if value.is_integer():
            return str(int(value))
        return f"{value:.3f}"
    text = str(value)
    return text.replace("|", "\\|").replace("\n", " ")


def label_text(sample: dict[str, object], keys: list[str]) -> list[str]:
    labels = sample["labels"]
    assert isinstance(labels, dict)
    return [str(labels.get(key, "")) for key in keys]


def metric_table(samples: list[dict[str, object]], name: str, label_keys: list[str], limit: int = 20) -> list[list[object]]:
    output = []
    for sample in rows(samples, name, limit):
        value = float(sample["value"])
        if value == 0:
            continue
        output.append(label_text(sample, label_keys) + [value])
    return output


def namespace_summary(governance: dict[str, object]) -> list[list[object]]:
    output = []
    for namespace in governance.get("namespaces", []) or []:
        if not isinstance(namespace, dict):
            continue
        limits = namespace.get("limits") if isinstance(namespace.get("limits"), dict) else {}
        output.append([
            namespace.get("name", ""),
            namespace.get("readOnly", False),
            len(namespace.get("allowedKeyPrefixes", []) or []),
            len(namespace.get("disabledKeys", []) or []),
            len(namespace.get("keyRules", []) or []),
            limits.get("maxConnections", 0),
            limits.get("maxQps", 0),
            limits.get("maxInflight", 0),
        ])
    return output


def hot_key_rows(items: object) -> list[list[object]]:
    output = []
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict):
            continue
        output.append([item.get("namespace", ""), item.get("command", ""), item.get("key", ""), item.get("count", 0)])
    return output


def large_key_rows(items: object) -> list[list[object]]:
    output = []
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict):
            continue
        output.append([
            item.get("namespace", ""),
            item.get("command", ""),
            item.get("key", ""),
            item.get("count", 0),
            item.get("maxRequestBytes", 0),
            item.get("maxResponseBytes", 0),
        ])
    return output


def slow_query_rows(items: object) -> list[list[object]]:
    output = []
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict):
            continue
        output.append([
            item.get("namespace", ""),
            item.get("command", ""),
            item.get("key", ""),
            item.get("count", 0),
            item.get("maxEndToEndMillis", 0),
            item.get("maxBackendMillis", 0),
        ])
    return output


def response_bytes_rows(samples: list[dict[str, object]]) -> list[list[object]]:
    counts = select(samples, "redis_proxy_response_bytes_count")
    sums = select(samples, "redis_proxy_response_bytes_sum")
    sum_by_command = {tuple(sorted(sample["labels"].items())): float(sample["value"]) for sample in sums}  # type: ignore[union-attr]
    output = []
    for sample in counts:
        labels = sample["labels"]
        assert isinstance(labels, dict)
        count = float(sample["value"])
        key = tuple(sorted(labels.items()))
        total_bytes = sum_by_command.get(key, 0.0)
        avg = total_bytes / count if count else 0.0
        output.append([labels.get("command", ""), count, total_bytes, avg])
    return sorted(output, key=lambda row: float(row[1]), reverse=True)


def risk_notes(samples: list[dict[str, object]]) -> list[str]:
    risks = []
    if total(samples, "redis_proxy_governance_reject_total") > 0:
        risks.append("存在命令或 key prefix 治理拒绝，建议核对 namespace 规则和研发接入规范。")
    if total(samples, "redis_proxy_namespace_limit_reject_total") > 0:
        risks.append("存在 namespace 限流拒绝，建议结合业务 QPS 和连接模型复核限额。")
    if total(samples, "redis_proxy_key_governance_reject_total") > 0:
        risks.append("存在 key 级禁用或滑动窗口限流拒绝，建议检查热点 key 或灰度规则。")
    if total(samples, "redis_proxy_hot_key_dropped_total") > 0:
        risks.append("热 key 跟踪容量已满并发生丢弃，建议增大 maxTrackedKeys 或缩短窗口。")
    if total(samples, "redis_proxy_large_key_dropped_total") > 0:
        risks.append("大 key 跟踪容量已满并发生丢弃，建议增大 maxTrackedKeys 或缩短窗口。")
    if total(samples, "redis_proxy_large_key_unsupported_total") > 0:
        risks.append("存在无法解析 key 位置的大请求/响应，建议扩展 key parser 或单独审计该命令。")
    if total(samples, "redis_proxy_large_response_total") > 0:
        risks.append("存在大 response 命中，建议结合 /debug/large-keys 判断是否需要治理或拆分 value。")
    if total(samples, "redis_proxy_slow_query_observed_total") > 0:
        risks.append("存在慢查询命中，建议结合 /debug/slow-queries 判断是 Redis 后端耗时还是 proxy 排队导致。")
    return risks


def write_report(args: argparse.Namespace) -> pathlib.Path:
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    output_dir = args.output_dir or pathlib.Path("reports") / f"governance-observability-{stamp}"
    output_dir.mkdir(parents=True, exist_ok=True)

    admin = args.admin_url.rstrip("/")
    metrics_url = args.metrics_url
    if not metrics_url:
        status, text = fetch_text(f"{admin}/metrics")
        if status >= 400:
            metrics_url = f"{admin}/actuator/prometheus"
            status, text = fetch_text(metrics_url)
        else:
            metrics_url = f"{admin}/metrics"
    else:
        status, text = fetch_text(metrics_url)
    if status >= 400:
        raise RuntimeError(f"failed to fetch metrics from {metrics_url}: HTTP {status}")

    health_status, health_text = fetch_text(f"{admin}/healthz")
    ready_status, ready_text = fetch_text(f"{admin}/readyz")
    route_status, route_snapshot = fetch_json(f"{admin}/debug/route-snapshot")
    hot_status, hot_keys = fetch_json(f"{admin}/debug/hot-keys?limit={args.hot_limit}")
    large_status, large_keys = fetch_json(f"{admin}/debug/large-keys?limit={args.large_limit}")
    slow_status, slow_queries = fetch_json(f"{admin}/debug/slow-queries?limit={args.slow_limit}")

    samples = parse_metrics(text)
    sanitized_route = clean(route_snapshot)
    governance = sanitized_route.get("governance", {}) if isinstance(sanitized_route, dict) else {}
    if not isinstance(governance, dict):
        governance = {}

    (output_dir / "metrics.prom").write_text(text)
    (output_dir / "route-snapshot.json").write_text(json.dumps(sanitized_route, ensure_ascii=False, indent=2) + "\n")
    (output_dir / "hot-keys.json").write_text(json.dumps(clean(hot_keys), ensure_ascii=False, indent=2) + "\n")
    (output_dir / "large-keys.json").write_text(json.dumps(clean(large_keys), ensure_ascii=False, indent=2) + "\n")
    (output_dir / "slow-queries.json").write_text(json.dumps(clean(slow_queries), ensure_ascii=False, indent=2) + "\n")

    summary = {
        "generatedAt": dt.datetime.now().isoformat(timespec="seconds"),
        "adminUrl": admin,
        "metricsUrl": metrics_url,
        "healthz": {"status": health_status, "body": health_text.strip()},
        "readyz": {"status": ready_status, "body": ready_text.strip()},
        "routeSnapshotStatus": route_status,
        "hotKeysStatus": hot_status,
        "largeKeysStatus": large_status,
        "slowQueriesStatus": slow_status,
        "routeSnapshot": sanitized_route,
        "metrics": {
            "authTotal": total(samples, "redis_proxy_auth_total"),
            "governanceRejectTotal": total(samples, "redis_proxy_governance_reject_total"),
            "governanceWarnTotal": total(samples, "redis_proxy_governance_warn_total"),
            "namespaceLimitRejectTotal": total(samples, "redis_proxy_namespace_limit_reject_total"),
            "keyGovernanceRejectTotal": total(samples, "redis_proxy_key_governance_reject_total"),
            "hotKeyTracked": gauge(samples, "redis_proxy_hot_key_tracked_keys"),
            "largeKeyTracked": gauge(samples, "redis_proxy_large_key_tracked_keys"),
            "largeResponseTotal": total(samples, "redis_proxy_large_response_total"),
            "slowQueryTracked": gauge(samples, "redis_proxy_slow_query_tracked_keys"),
            "slowQueryObservedTotal": total(samples, "redis_proxy_slow_query_observed_total"),
        },
    }
    (output_dir / "summary.json").write_text(json.dumps(clean(summary), ensure_ascii=False, indent=2) + "\n")

    route = sanitized_route if isinstance(sanitized_route, dict) else {}
    report = []
    report.append(f"# {args.title}\n")
    report.append(f"Generated at: {summary['generatedAt']}\n")
    report.append("## 基础状态\n")
    report.append(table(
        ["项目", "值"],
        [
            ["Admin URL", admin],
            ["Metrics URL", metrics_url],
            ["healthz", f"HTTP {health_status} {health_text.strip()}"],
            ["readyz", f"HTTP {ready_status} {ready_text.strip()}"],
            ["Route epoch", route.get("epoch", "n/a")],
            ["Mode", route.get("mode", "n/a")],
            ["Default cluster", route.get("defaultCluster", "n/a")],
            ["Route clusters", ", ".join(route.get("routeClusters", []) or [])],
            ["Governance enabled", governance.get("enabled", False)],
        ],
    ))

    report.append("## 治理配置摘要\n")
    report.append(table(
        ["Namespace", "Read only", "Allowed prefixes", "Disabled keys", "Key rules", "Max conn", "Max QPS", "Max inflight"],
        namespace_summary(governance),
    ))

    report.append("## 治理命中\n")
    governance_rows = []
    governance_rows += [["auth", *row] for row in metric_table(samples, "redis_proxy_auth_total", ["namespace", "result"])]
    governance_rows += [["reject", *row] for row in metric_table(samples, "redis_proxy_governance_reject_total", ["namespace", "command", "reason"])]
    governance_rows += [["warn", *row] for row in metric_table(samples, "redis_proxy_governance_warn_total", ["namespace", "command", "reason"])]
    governance_rows += [["namespace-limit", *row] for row in metric_table(samples, "redis_proxy_namespace_limit_reject_total", ["namespace", "limit"])]
    report.append(table(["Type", "Label 1", "Label 2", "Label 3", "Value"], normalize_rows(governance_rows, 5)))

    key_rows = []
    key_rows += [["key-reject", *row] for row in metric_table(samples, "redis_proxy_key_governance_reject_total", ["namespace", "rule", "command", "reason"])]
    key_rows += [["key-decision", *row] for row in metric_table(samples, "redis_proxy_key_governance_decisions_total", ["namespace", "rule", "command", "result", "reason"])]
    report.append(table(["Type", "Namespace", "Rule", "Command", "Result/Reason", "Reason/Value", "Value"], normalize_rows(key_rows, 7)))

    report.append("## 访问特征\n")
    report.append("### 热 key TopN\n")
    report.append(table(["Namespace", "Command", "Key", "Count"], hot_key_rows(hot_keys)))
    report.append("### 大 key TopN\n")
    report.append(table(["Namespace", "Command", "Key", "Count", "Max request bytes", "Max response bytes"], large_key_rows(large_keys)))
    report.append("### 慢查询 TopN\n")
    report.append(table(["Namespace", "Command", "Key", "Count", "Max E2E ms", "Max backend ms"], slow_query_rows(slow_queries)))
    feature_rows = []
    feature_rows += [["hot-observed", *row] for row in metric_table(samples, "redis_proxy_hot_key_observed_total", ["namespace", "command"])]
    feature_rows += [["hot-dropped", *row] for row in metric_table(samples, "redis_proxy_hot_key_dropped_total", ["namespace", "command"])]
    feature_rows += [["large-observed", *row] for row in metric_table(samples, "redis_proxy_large_key_observed_total", ["namespace", "command", "direction"])]
    feature_rows += [["large-dropped", *row] for row in metric_table(samples, "redis_proxy_large_key_dropped_total", ["namespace", "command"])]
    feature_rows += [["large-unsupported", *row] for row in metric_table(samples, "redis_proxy_large_key_unsupported_total", ["command", "direction"])]
    feature_rows += [["slow-observed", *row] for row in metric_table(samples, "redis_proxy_slow_query_observed_total", ["namespace", "command", "trigger"])]
    feature_rows += [["slow-dropped", *row] for row in metric_table(samples, "redis_proxy_slow_query_dropped_total", ["namespace", "command"])]
    feature_rows += [["slow-unsupported", *row] for row in metric_table(samples, "redis_proxy_slow_query_unsupported_total", ["command"])]
    report.append("### 观测指标\n")
    report.append(table(["Type", "Label 1", "Label 2", "Label 3", "Value"], normalize_rows(feature_rows, 5)))

    report.append("## 大响应与响应大小\n")
    report.append(table(
        ["Metric", "Value"],
        [
            ["largeResponseThresholdBytes", gauge(samples, "redis_proxy_large_response_threshold_bytes")],
            ["largeKeyRequestThresholdBytes", gauge(samples, "redis_proxy_large_key_request_threshold_bytes")],
            ["largeKeyResponseThresholdBytes", gauge(samples, "redis_proxy_large_key_response_threshold_bytes")],
            ["largeResponseTotal", total(samples, "redis_proxy_large_response_total")],
            ["hotKeyTracked", gauge(samples, "redis_proxy_hot_key_tracked_keys")],
            ["largeKeyTracked", gauge(samples, "redis_proxy_large_key_tracked_keys")],
            ["slowQueryTracked", gauge(samples, "redis_proxy_slow_query_tracked_keys")],
        ],
    ))
    report.append("### Response bytes\n")
    report.append(table(["Command", "Count", "Sum bytes", "Avg bytes"], response_bytes_rows(samples)))

    report.append("## 风险提示\n")
    notes = risk_notes(samples)
    if notes:
        report.extend([f"- {note}\n" for note in notes])
    else:
        report.append("- 未发现治理拒绝、限流、大 response 或观测容量丢弃信号。\n")

    report_text = "\n".join(report)
    (output_dir / "report.md").write_text(report_text)
    print(output_dir)
    return output_dir


def normalize_rows(data: list[list[object]], width: int) -> list[list[object]]:
    output = []
    for row in data:
        next_row = list(row[:width])
        while len(next_row) < width:
            next_row.insert(-1 if next_row else 0, "")
        output.append(next_row)
    return output


def self_test() -> int:
    fixture = """
# HELP redis_proxy_auth_total x
redis_proxy_auth_total{namespace="app-a",result="success"} 2
redis_proxy_governance_reject_total{namespace="app-a",command="FLUSHALL",reason="global_denied_command"} 1
redis_proxy_key_governance_decisions_total{namespace="app-a",rule="hot",command="GET",result="reject",reason="qps_limit"} 3
redis_proxy_response_bytes_count{command="GET"} 2
redis_proxy_response_bytes_sum{command="GET"} 512
redis_proxy_large_key_observed_total{namespace="app-a",command="GET",direction="response"} 1
redis_proxy_large_key_tracked_keys 1
redis_proxy_slow_query_observed_total{namespace="app-a",command="GET",trigger="both"} 1
redis_proxy_slow_query_tracked_keys 1
"""
    samples = parse_metrics(fixture)
    assert total(samples, "redis_proxy_auth_total") == 2
    assert total(samples, "redis_proxy_governance_reject_total") == 1
    assert metric_table(samples, "redis_proxy_key_governance_decisions_total", ["namespace", "rule", "command", "result", "reason"])[0][-1] == 3
    assert response_bytes_rows(samples)[0][3] == 256
    assert clean({"token": "x", "nested": [{"password": "y", "ok": 1}]}) == {"nested": [{"ok": 1}]}
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a local Redis proxy governance and observability report.")
    parser.add_argument("--admin-url", default="http://127.0.0.1:8080")
    parser.add_argument("--metrics-url")
    parser.add_argument("--output-dir", type=pathlib.Path)
    parser.add_argument("--title", default="Redis Proxy Governance And Observability Report")
    parser.add_argument("--hot-limit", type=int, default=20)
    parser.add_argument("--large-limit", type=int, default=100)
    parser.add_argument("--slow-limit", type=int, default=100)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        write_report(args)
        return 0
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
