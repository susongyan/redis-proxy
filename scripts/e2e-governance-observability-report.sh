#!/usr/bin/env bash
set -euo pipefail

DATAPLANE="${1:-go}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/redis-proxy-e2e-report-${DATAPLANE}}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"

case "${DATAPLANE}" in
  go|java) ;;
  *) echo "Usage: $0 go|java" >&2; exit 1 ;;
esac

rm -rf "${LOG_DIR}" && mkdir -p "${LOG_DIR}"

cleanup() {
  [[ -n "${PROXY_PID:-}" ]] && kill "${PROXY_PID}" >/dev/null 2>&1 || true
  [[ -n "${CP_PID:-}" ]] && kill "${CP_PID}" >/dev/null 2>&1 || true
  docker rm -f redis-proxy-report-a >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in 6379 8080 8090 63830; do
  lsof -ti tcp:${port} | xargs kill >/dev/null 2>&1 || true
done

docker run -d --name redis-proxy-report-a -p 63830:6379 "${REDIS_IMAGE}" >/dev/null
for i in {1..30}; do
  docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63830 ping >/dev/null 2>&1 && break
  sleep 1
done

python3 - <<'PY'
import socket

def bulk(value):
    if isinstance(value, str):
        value = value.encode()
    return b"$" + str(len(value)).encode() + b"\r\n" + value + b"\r\n"

def cmd(*values):
    return b"*" + str(len(values)).encode() + b"\r\n" + b"".join(bulk(v) for v in values)

with socket.create_connection(("127.0.0.1", 63830), timeout=5) as sock:
    payload = (
        cmd("SET", "app-a:1", "v1") +
        cmd("SET", "app-a:big", "x" * 256) +
        cmd("SET", "limited:1", "v1")
    )
    sock.sendall(payload)
    sock.settimeout(5)
    data = b""
    while data.count(b"+OK\r\n") < 3:
        data += sock.recv(4096)
PY

(cd "${ROOT}/redis-proxy-control-plane-java" && mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run -Dspring-boot.run.arguments=--server.port=8090 >"${LOG_DIR}/control-plane.log" 2>&1) &
CP_PID=$!
for i in {1..60}; do
  curl -fsS http://127.0.0.1:8090/healthz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${CP_PID}" >/dev/null 2>&1 || { tail -160 "${LOG_DIR}/control-plane.log"; exit 1; }
done

python3 - <<'PY' >"${LOG_DIR}/config.json"
import json
config = {
  "server": {"listen": "0.0.0.0:6379"},
  "admin": {"listen": "0.0.0.0:8080"},
  "mode": "standalone",
  "backends": {"clusters": [{"name": "redis-a", "nodes": ["127.0.0.1:63830"], "pool": {"connectionsPerNode": 2, "maxInflightPerConnection": 128}}]},
  "routing": {"defaultCluster": "redis-a", "routeEpoch": 1, "clusterSlotsRefreshIntervalSeconds": 30, "rules": []},
  "limits": {"maxPipelineDepth": 1024, "maxRequestBytes": 10485760, "maxResponseBytes": 104857600, "largeResponseBytes": 64},
  "analysis": {
    "hotKey": {"enabled": True, "windowSeconds": 60, "bucketMillis": 1000, "maxTrackedKeys": 10000, "metricsTopN": 5},
    "largeKey": {"enabled": True, "requestBytesThreshold": 64, "responseBytesThreshold": 64, "windowSeconds": 300, "bucketMillis": 1000, "maxTrackedKeys": 10000, "debugTopN": 20},
    "slowQuery": {"enabled": True, "endToEndThresholdMillis": 1, "backendThresholdMillis": 1, "windowSeconds": 300, "bucketMillis": 1000, "maxTrackedKeys": 10000, "debugTopN": 20}
  },
  "governance": {
    "enabled": True,
    "requireAuth": True,
    "keyLimitWindowMillis": 1000,
    "keyLimitBucketMillis": 100,
    "commandPolicy": {"deniedCommands": ["FLUSHALL", "FLUSHDB", "CONFIG", "SHUTDOWN", "DEBUG", "MODULE"], "warnOnlyCommands": ["KEYS", "EVAL", "SCRIPT"]},
    "namespaces": [
      {"name": "app-a", "token": "token-a", "readOnly": False, "allowedKeyPrefixes": ["app-a:"], "limits": {"maxConnections": 0, "maxQps": 0, "maxInflight": 0}, "disabledKeys": ["app-a:blocked"], "keyRules": [{"name": "hot", "keyPrefix": "app-a:hot:", "maxQps": 1}]},
      {"name": "limited", "token": "token-l", "readOnly": False, "allowedKeyPrefixes": ["limited:"], "limits": {"maxConnections": 0, "maxQps": 1, "maxInflight": 0}}
    ]
  }
}
print(json.dumps(config))
PY

curl -fsS -X PUT -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/config.json" http://127.0.0.1:8090/api/v1/config >/dev/null

if [[ "${DATAPLANE}" == "go" ]]; then
  cat >"${LOG_DIR}/proxy.yaml" <<'YAML'
server:
  listen: "0.0.0.0:6379"
admin:
  listen: "0.0.0.0:8080"
mode: "standalone"
backends:
  clusters:
    - name: "redis-a"
      nodes: ["127.0.0.1:63830"]
      pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
routing: {defaultCluster: "redis-a", routeEpoch: 1}
controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 5, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600, largeResponseBytes: 64}
analysis:
  hotKey: {enabled: true, windowSeconds: 60, bucketMillis: 1000, maxTrackedKeys: 10000, metricsTopN: 5}
  largeKey: {enabled: true, requestBytesThreshold: 64, responseBytesThreshold: 64, windowSeconds: 300, bucketMillis: 1000, maxTrackedKeys: 10000, debugTopN: 20}
  slowQuery: {enabled: true, endToEndThresholdMillis: 1, backendThresholdMillis: 1, windowSeconds: 300, bucketMillis: 1000, maxTrackedKeys: 10000, debugTopN: 20}
governance:
  enabled: true
  requireAuth: true
  keyLimitWindowMillis: 1000
  keyLimitBucketMillis: 100
  commandPolicy:
    deniedCommands: ["FLUSHALL", "FLUSHDB", "CONFIG", "SHUTDOWN", "DEBUG", "MODULE"]
    warnOnlyCommands: ["KEYS", "EVAL", "SCRIPT"]
  namespaces:
    - name: "app-a"
      token: "token-a"
      allowedKeyPrefixes: ["app-a:"]
      limits: {maxConnections: 0, maxQps: 0, maxInflight: 0}
      disabledKeys: ["app-a:blocked"]
      keyRules:
        - {name: "hot", keyPrefix: "app-a:hot:", maxQps: 1}
    - name: "limited"
      token: "token-l"
      allowedKeyPrefixes: ["limited:"]
      limits: {maxConnections: 0, maxQps: 1, maxInflight: 0}
YAML
  (cd "${ROOT}/redis-proxy-dataplane-go" && go run ./cmd/proxy -config "${LOG_DIR}/proxy.yaml" >"${LOG_DIR}/proxy.log" 2>&1) &
else
  cat >"${LOG_DIR}/proxy.yml" <<'YAML'
server:
  port: 8080
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
proxy:
  server: {listen: "0.0.0.0:6379", bossThreads: 1, workerThreads: 0}
  admin: {listen: "0.0.0.0:8080"}
  mode: "standalone"
  backends:
    clusters:
      - name: "redis-a"
        nodes: ["127.0.0.1:63830"]
        pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
  routing: {defaultCluster: "redis-a", routeEpoch: 1}
  controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 5, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
  limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600, largeResponseBytes: 64}
  analysis:
    hotKey: {enabled: true, windowSeconds: 60, bucketMillis: 1000, maxTrackedKeys: 10000, metricsTopN: 5}
    largeKey: {enabled: true, requestBytesThreshold: 64, responseBytesThreshold: 64, windowSeconds: 300, bucketMillis: 1000, maxTrackedKeys: 10000, debugTopN: 20}
    slowQuery: {enabled: true, endToEndThresholdMillis: 1, backendThresholdMillis: 1, windowSeconds: 300, bucketMillis: 1000, maxTrackedKeys: 10000, debugTopN: 20}
  governance:
    enabled: true
    requireAuth: true
    keyLimitWindowMillis: 1000
    keyLimitBucketMillis: 100
    commandPolicy:
      deniedCommands: ["FLUSHALL", "FLUSHDB", "CONFIG", "SHUTDOWN", "DEBUG", "MODULE"]
      warnOnlyCommands: ["KEYS", "EVAL", "SCRIPT"]
    namespaces:
      - name: "app-a"
        token: "token-a"
        allowedKeyPrefixes: ["app-a:"]
        limits: {maxConnections: 0, maxQps: 0, maxInflight: 0}
        disabledKeys: ["app-a:blocked"]
        keyRules:
          - {name: "hot", keyPrefix: "app-a:hot:", maxQps: 1}
      - name: "limited"
        token: "token-l"
        allowedKeyPrefixes: ["limited:"]
        limits: {maxConnections: 0, maxQps: 1, maxInflight: 0}
YAML
  (cd "${ROOT}/redis-proxy-dataplane-java" && mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run -Dspring-boot.run.arguments=--spring.config.location="${LOG_DIR}/proxy.yml" >"${LOG_DIR}/proxy.log" 2>&1) &
fi
PROXY_PID=$!

for i in {1..90}; do
  curl -fsS http://127.0.0.1:8080/readyz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${PROXY_PID}" >/dev/null 2>&1 || { tail -160 "${LOG_DIR}/proxy.log"; exit 1; }
done

python3 - <<'PY'
import socket

def bulk(value):
    if isinstance(value, str):
        value = value.encode()
    return b"$" + str(len(value)).encode() + b"\r\n" + value + b"\r\n"

def cmd(*values):
    return b"*" + str(len(values)).encode() + b"\r\n" + b"".join(bulk(v) for v in values)

def exchange(payload):
    with socket.create_connection(("127.0.0.1", 6379), timeout=5) as sock:
        sock.sendall(payload)
        sock.settimeout(0.3)
        data = b""
        while True:
            try:
                chunk = sock.recv(4096)
            except TimeoutError:
                break
            if not chunk:
                break
            data += chunk
        return data

def assert_contains(label, data, needle):
    if needle not in data:
        raise SystemExit(f"{label}: expected {needle!r} in {data!r}")

assert_contains("unauth", exchange(cmd("GET", "app-a:1")), b"-NOAUTH Authentication required")
assert_contains("bad auth", exchange(cmd("AUTH", "app-a", "bad-token")), b"-ERR invalid namespace")
assert_contains("good auth and hot key", exchange(cmd("AUTH", "app-a", "token-a") + cmd("GET", "app-a:1") + cmd("GET", "app-a:1") + cmd("GET", "app-a:big")), b"$256")
assert_contains("global deny", exchange(cmd("AUTH", "app-a", "token-a") + cmd("FLUSHALL")), b"-ERR command denied by proxy governance")
assert_contains("key limit", exchange(cmd("AUTH", "app-a", "token-a") + cmd("GET", "app-a:hot:1") + cmd("GET", "app-a:hot:2")), b"-ERR key limited by proxy governance")
assert_contains("namespace limit", exchange(cmd("AUTH", "limited", "token-l") + cmd("GET", "limited:1") + cmd("GET", "limited:1")), b"-ERR request limited by proxy governance")
PY

REPORT_DIR="$(python3 "${ROOT}/scripts/generate-governance-observability-report.py" --admin-url http://127.0.0.1:8080 --output-dir "${LOG_DIR}/report" --title "Redis Proxy Governance Observability E2E ${DATAPLANE}")"
REPORT="${REPORT_DIR}/report.md"
SUMMARY="${REPORT_DIR}/summary.json"
METRICS="${REPORT_DIR}/metrics.prom"

rg 'Route epoch|治理命中|热 key TopN|大 key TopN|慢查询 TopN|大响应与响应大小|风险提示' "${REPORT}" >/dev/null
rg 'app-a:1' "${REPORT}" >/dev/null
rg 'app-a:big' "${REPORT}" >/dev/null
rg 'slowQueryObservedTotal|slow-observed' "${REPORT}" >/dev/null
rg 'largeResponseTotal' "${REPORT}" >/dev/null
rg 'global_denied_command|qps_limit|namespace-limit' "${REPORT}" >/dev/null

if rg 'token-a|token-l|bad-token' "${REPORT}" "${SUMMARY}" >/dev/null; then
  echo "report output leaked namespace token" >&2
  exit 1
fi
if rg '^redis_proxy_large_key_.*app-a:big' "${METRICS}" >/dev/null; then
  echo "large key metrics leaked concrete key label" >&2
  exit 1
fi

echo "governance observability report e2e passed for ${DATAPLANE}: ${REPORT_DIR}"
