#!/usr/bin/env bash
set -euo pipefail

DATAPLANE="${1:-go}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/redis-proxy-e2e-observability-collector-${DATAPLANE}}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"

case "${DATAPLANE}" in
  go|java) ;;
  *) echo "Usage: $0 go|java" >&2; exit 1 ;;
esac

rm -rf "${LOG_DIR}" && mkdir -p "${LOG_DIR}"

cleanup() {
  [[ -n "${PROXY_PID:-}" ]] && kill "${PROXY_PID}" >/dev/null 2>&1 || true
  [[ -n "${CP_PID:-}" ]] && kill "${CP_PID}" >/dev/null 2>&1 || true
  docker rm -f redis-proxy-observability-collector-a >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in 6379 8080 8090 63840; do
  lsof -ti tcp:${port} | xargs kill >/dev/null 2>&1 || true
done

docker run -d --name redis-proxy-observability-collector-a -p 63840:6379 "${REDIS_IMAGE}" >/dev/null
for i in {1..30}; do
  docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63840 ping >/dev/null 2>&1 && break
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

with socket.create_connection(("127.0.0.1", 63840), timeout=5) as sock:
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
  "backends": {"clusters": [{"name": "redis-a", "nodes": ["127.0.0.1:63840"], "pool": {"connectionsPerNode": 2, "maxInflightPerConnection": 128}}]},
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
      nodes: ["127.0.0.1:63840"]
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
        nodes: ["127.0.0.1:63840"]
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

cat >"${LOG_DIR}/target.json" <<JSON
{"proxyId":"${DATAPLANE}-proxy-1","adminUrl":"http://127.0.0.1:8080","dataplane":"${DATAPLANE}","cluster":"redis-a","pollIntervalSeconds":1,"serviceNamespace":"redis-proxy","serviceName":"redis-proxy-dataplane","serviceInstanceId":"${DATAPLANE}-proxy-1","deploymentEnvironmentName":"local-e2e"}
JSON
curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/target.json" http://127.0.0.1:8090/api/v1/observability/targets >"${LOG_DIR}/target-response.json"

for i in {1..30}; do
  curl -fsS http://127.0.0.1:8090/api/v1/observability/summary >"${LOG_DIR}/summary.json"
  python3 - "${LOG_DIR}/summary.json" <<'PY' && break || true
import json
import sys
data = json.load(open(sys.argv[1]))
targets = data.get("targets", [])
totals = data.get("totals", {})
if targets and targets[0].get("healthy") and totals.get("governanceRejectTotal", 0) >= 1 and totals.get("largeResponseTotal", 0) >= 1:
    raise SystemExit(0)
raise SystemExit(1)
PY
  sleep 1
done

curl -fsS "http://127.0.0.1:8090/api/v1/observability/hot-keys?namespace=app-a&limit=10" >"${LOG_DIR}/hot-keys.json"
curl -fsS "http://127.0.0.1:8090/api/v1/observability/large-keys?namespace=app-a&limit=10" >"${LOG_DIR}/large-keys.json"
curl -fsS "http://127.0.0.1:8090/api/v1/observability/slow-queries?limit=10" >"${LOG_DIR}/slow-queries.json"

python3 - <<'PY' "${LOG_DIR}/summary.json" "${LOG_DIR}/hot-keys.json" "${LOG_DIR}/large-keys.json" "${LOG_DIR}/slow-queries.json"
import json
import sys
summary = json.load(open(sys.argv[1]))
hot = json.load(open(sys.argv[2]))
large = json.load(open(sys.argv[3]))
slow = json.load(open(sys.argv[4]))
if not summary["targets"][0]["healthy"]:
    raise SystemExit(f"collector target is not healthy: {summary!r}")
attrs = summary["targets"][0].get("resourceAttributes", {})
expected_attrs = {
    "service.namespace": "redis-proxy",
    "service.name": "redis-proxy-dataplane",
    "deployment.environment.name": "local-e2e",
    "redis.proxy.cluster": "redis-a",
}
for key, expected in expected_attrs.items():
    if attrs.get(key) != expected:
        raise SystemExit(f"summary missing OTel resource attribute {key}={expected!r}: {attrs!r}")
totals = summary["totals"]
for key in ("authTotal", "governanceRejectTotal", "namespaceLimitRejectTotal", "keyGovernanceRejectTotal", "largeResponseTotal", "slowQueryObservedTotal"):
    if totals.get(key, 0) < 1:
        raise SystemExit(f"summary missing {key}: {summary!r}")
if not any(item.get("key") == "app-a:1" for item in hot):
    raise SystemExit(f"hot key API missing app-a:1: {hot!r}")
if not hot[0].get("resourceAttributes", {}).get("service.instance.id"):
    raise SystemExit(f"hot key API missing OTel resource attributes: {hot!r}")
if not any(item.get("key") == "app-a:big" and item.get("maxResponseBytes", 0) >= 256 for item in large):
    raise SystemExit(f"large key API missing app-a:big: {large!r}")
if not large[0].get("resourceAttributes", {}).get("service.instance.id"):
    raise SystemExit(f"large key API missing OTel resource attributes: {large!r}")
if not any(str(item.get("key", "")).startswith("app-a:") and item.get("maxEndToEndMillis", 0) >= 1 for item in slow):
    raise SystemExit(f"slow query API missing app-a key: {slow!r}")
if not slow[0].get("resourceAttributes", {}).get("service.instance.id"):
    raise SystemExit(f"slow query API missing OTel resource attributes: {slow!r}")
PY

if rg 'token-a|token-l|bad-token' "${LOG_DIR}/summary.json" "${LOG_DIR}/hot-keys.json" "${LOG_DIR}/large-keys.json" "${LOG_DIR}/slow-queries.json" >/dev/null; then
  echo "observability API leaked namespace token" >&2
  exit 1
fi

echo "observability collector e2e passed for ${DATAPLANE}"
