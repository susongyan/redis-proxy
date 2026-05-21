#!/usr/bin/env bash
set -euo pipefail

DATAPLANE="${1:-go}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/redis-proxy-e2e-governance-${DATAPLANE}}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"

case "${DATAPLANE}" in
  go|java) ;;
  *) echo "Usage: $0 go|java" >&2; exit 1 ;;
esac

rm -rf "${LOG_DIR}" && mkdir -p "${LOG_DIR}"

cleanup() {
  [[ -n "${PROXY_PID:-}" ]] && kill "${PROXY_PID}" >/dev/null 2>&1 || true
  [[ -n "${CP_PID:-}" ]] && kill "${CP_PID}" >/dev/null 2>&1 || true
  docker rm -f redis-proxy-governance-a >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in 6379 8080 8090 63810; do
  lsof -ti tcp:${port} | xargs kill >/dev/null 2>&1 || true
done

docker run -d --name redis-proxy-governance-a -p 63810:6379 "${REDIS_IMAGE}" >/dev/null
for i in {1..30}; do
  docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63810 ping >/dev/null 2>&1 && break
  sleep 1
done
docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63810 set reader:seed seeded >/dev/null

(cd "${ROOT}/redis-proxy-control-plane-java" && mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run -Dspring-boot.run.arguments=--server.port=8090 >"${LOG_DIR}/control-plane.log" 2>&1) &
CP_PID=$!
for i in {1..60}; do
  curl -fsS http://127.0.0.1:8090/healthz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${CP_PID}" >/dev/null 2>&1 || { tail -120 "${LOG_DIR}/control-plane.log"; exit 1; }
done

python3 - <<'PY' >"${LOG_DIR}/config-epoch1.json"
import json
base = {
  "server": {"listen": "0.0.0.0:6379"},
  "admin": {"listen": "0.0.0.0:8080"},
  "mode": "standalone",
  "backends": {"clusters": [{"name": "redis-a", "nodes": ["127.0.0.1:63810"], "pool": {"connectionsPerNode": 2, "maxInflightPerConnection": 128}}]},
  "routing": {"defaultCluster": "redis-a", "routeEpoch": 1, "clusterSlotsRefreshIntervalSeconds": 30, "rules": []},
  "limits": {"maxPipelineDepth": 1024, "maxRequestBytes": 10485760, "maxResponseBytes": 104857600},
  "governance": {
    "enabled": True,
    "requireAuth": True,
    "keyLimitWindowMillis": 1000,
    "keyLimitBucketMillis": 100,
    "commandPolicy": {"deniedCommands": ["FLUSHALL", "FLUSHDB", "CONFIG", "SHUTDOWN", "DEBUG", "MODULE"], "warnOnlyCommands": ["KEYS", "EVAL", "SCRIPT"]},
    "namespaces": [
      {"name": "app-a", "token": "token-a", "readOnly": False, "allowedKeyPrefixes": ["app-a:"], "limits": {"maxConnections": 0, "maxQps": 0, "maxInflight": 0}, "disabledKeys": ["app-a:blocked"], "keyRules": [{"name": "hot", "keyPrefix": "app-a:hot:", "maxQps": 1}]},
      {"name": "reader", "token": "token-r", "readOnly": True, "allowedKeyPrefixes": ["reader:"]},
      {"name": "limited", "token": "token-l", "readOnly": False, "allowedKeyPrefixes": ["limited:"], "limits": {"maxConnections": 0, "maxQps": 1, "maxInflight": 0}}
    ]
  }
}
print(json.dumps(base))
PY
LOG_PATH="${LOG_DIR}" python3 - <<'PY' >"${LOG_DIR}/publish-epoch2.json"
import json
import os
base = json.load(open(os.path.join(os.environ["LOG_PATH"], "config-epoch1.json")))
base["routing"]["routeEpoch"] = 2
base["governance"]["namespaces"][0]["allowedKeyPrefixes"] = ["app-b:"]
base["governance"]["namespaces"][0]["disabledKeys"] = []
base["governance"]["namespaces"][0]["keyRules"] = []
print(json.dumps({"operator": "e2e", "reason": "governance dynamic switch", "config": base}))
PY

curl -fsS -X PUT -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/config-epoch1.json" http://127.0.0.1:8090/api/v1/config >/dev/null

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
      nodes: ["127.0.0.1:63810"]
      pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
routing: {defaultCluster: "redis-a", routeEpoch: 1}
controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 5, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600}
governance:
  enabled: true
  requireAuth: true
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
    - name: "reader"
      token: "token-r"
      readOnly: true
      allowedKeyPrefixes: ["reader:"]
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
        nodes: ["127.0.0.1:63810"]
        pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
  routing: {defaultCluster: "redis-a", routeEpoch: 1}
  controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 5, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
  limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600}
  governance:
    enabled: true
    requireAuth: true
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
      - name: "reader"
        token: "token-r"
        readOnly: true
        allowedKeyPrefixes: ["reader:"]
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
        sock.settimeout(5)
        data = b""
        while True:
            try:
                data += sock.recv(4096)
            except TimeoutError:
                break
            if not data or len(data) >= 4096:
                break
            if data.count(b"\r\n") >= payload.count(b"*"):
                break
        return data

def assert_contains(label, data, needle):
    if needle not in data:
        raise SystemExit(f"{label}: expected {needle!r} in {data!r}")

assert_contains("unauth", exchange(cmd("GET", "app-a:1")), b"-NOAUTH Authentication required")
assert_contains("auth-set-get", exchange(cmd("AUTH", "app-a", "token-a") + cmd("SET", "app-a:1", "v1") + cmd("GET", "app-a:1")), b"$2\r\nv1\r\n")
assert_contains("flush denied", exchange(cmd("AUTH", "app-a", "token-a") + cmd("FLUSHALL")), b"-ERR command denied by proxy governance")
assert_contains("key prefix denied", exchange(cmd("AUTH", "app-a", "token-a") + cmd("GET", "other:1")), b"-ERR key denied by proxy governance")
assert_contains("exact key disabled", exchange(cmd("AUTH", "app-a", "token-a") + cmd("GET", "app-a:blocked")), b"-ERR key disabled by proxy governance")
assert_contains("key sliding limit", exchange(cmd("AUTH", "app-a", "token-a") + cmd("GET", "app-a:hot:1") + cmd("GET", "app-a:hot:2")), b"-ERR key limited by proxy governance")
assert_contains("namespace qps limit", exchange(cmd("AUTH", "limited", "token-l") + cmd("GET", "limited:1") + cmd("GET", "limited:2")), b"-ERR request limited by proxy governance")
assert_contains("reader get", exchange(cmd("AUTH", "reader", "token-r") + cmd("GET", "reader:seed")), b"$6\r\nseeded\r\n")
assert_contains("reader set denied", exchange(cmd("AUTH", "reader", "token-r") + cmd("SET", "reader:1", "v")), b"-ERR command denied by proxy governance")
PY

curl -fsS "http://127.0.0.1:8080/debug/hot-keys?limit=5" >"${LOG_DIR}/hot-keys.json"
python3 - <<'PY' "${LOG_DIR}/hot-keys.json"
import json
import sys
items = json.load(open(sys.argv[1]))
if not any(item.get("key") == "app-a:1" and item.get("command") == "GET" and item.get("count", 0) >= 1 for item in items):
    raise SystemExit(f"hot key TopK missing app-a:1: {items!r}")
PY

curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/publish-epoch2.json" http://127.0.0.1:8090/api/v1/config/publish >/dev/null
for i in {1..50}; do
  epoch="$(curl -fsS http://127.0.0.1:8080/debug/route-snapshot | python3 -c 'import json,sys; print(json.load(sys.stdin)["epoch"])')"
  [[ "${epoch}" == "2" ]] && break
  sleep 0.2
done
[[ "${epoch}" == "2" ]] || { curl -fsS http://127.0.0.1:8080/debug/route-snapshot; exit 1; }

python3 - <<'PY'
import socket

def bulk(value):
    if isinstance(value, str):
        value = value.encode()
    return b"$" + str(len(value)).encode() + b"\r\n" + value + b"\r\n"

def cmd(*values):
    return b"*" + str(len(values)).encode() + b"\r\n" + b"".join(bulk(v) for v in values)

with socket.create_connection(("127.0.0.1", 6379), timeout=5) as sock:
    sock.sendall(cmd("AUTH", "app-a", "token-a") + cmd("GET", "app-a:1") + cmd("SET", "app-b:1", "v2"))
    sock.settimeout(5)
    data = b""
    while data.count(b"\r\n") < 3:
        data += sock.recv(4096)
if b"-ERR key denied by proxy governance" not in data or b"+OK\r\n" not in data:
    raise SystemExit(f"dynamic governance switch failed: {data!r}")
PY

if curl -fsS http://127.0.0.1:8080/debug/route-snapshot | grep -q 'token-a'; then
  echo "route snapshot leaked namespace token" >&2
  exit 1
fi

echo "governance e2e passed for ${DATAPLANE}"
