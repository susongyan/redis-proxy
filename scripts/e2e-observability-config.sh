#!/usr/bin/env bash
set -euo pipefail

DATAPLANE="${1:-go}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/redis-proxy-e2e-observability-${DATAPLANE}}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"

case "${DATAPLANE}" in
  go|java) ;;
  *) echo "Usage: $0 go|java" >&2; exit 1 ;;
esac

rm -rf "${LOG_DIR}" && mkdir -p "${LOG_DIR}"

cleanup() {
  [[ -n "${PROXY_PID:-}" ]] && kill "${PROXY_PID}" >/dev/null 2>&1 || true
  [[ -n "${CP_PID:-}" ]] && kill "${CP_PID}" >/dev/null 2>&1 || true
  docker rm -f redis-proxy-observability-a >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in 6379 8080 8090 63820; do
  lsof -ti tcp:${port} | xargs kill >/dev/null 2>&1 || true
done

docker run -d --name redis-proxy-observability-a -p 63820:6379 "${REDIS_IMAGE}" >/dev/null
for i in {1..30}; do
  docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63820 ping >/dev/null 2>&1 && break
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

with socket.create_connection(("127.0.0.1", 63820), timeout=5) as sock:
    payload = (
        cmd("SET", "obs:k1", "v1") +
        cmd("SET", "obs:k2", "v2") +
        cmd("SET", "obs:big", "x" * 128)
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

python3 - <<'PY' >"${LOG_DIR}/config-epoch1.json"
import json
config = {
  "server": {"listen": "0.0.0.0:6379"},
  "admin": {"listen": "0.0.0.0:8080"},
  "mode": "standalone",
  "backends": {"clusters": [{"name": "redis-a", "nodes": ["127.0.0.1:63820"], "pool": {"connectionsPerNode": 2, "maxInflightPerConnection": 128}}]},
  "routing": {"defaultCluster": "redis-a", "routeEpoch": 1, "clusterSlotsRefreshIntervalSeconds": 30, "rules": []},
  "limits": {"maxPipelineDepth": 1024, "maxRequestBytes": 10485760, "maxResponseBytes": 104857600, "largeResponseBytes": 100000},
  "analysis": {
    "hotKey": {"enabled": True, "windowSeconds": 60, "bucketMillis": 1000, "maxTrackedKeys": 1, "metricsTopN": 1},
    "largeKey": {"enabled": True, "requestBytesThreshold": 100000, "responseBytesThreshold": 100000, "windowSeconds": 300, "bucketMillis": 1000, "maxTrackedKeys": 10000, "debugTopN": 100}
  }
}
print(json.dumps(config))
PY

LOG_PATH="${LOG_DIR}" python3 - <<'PY' >"${LOG_DIR}/publish-epoch2.json"
import json
import os
config = json.load(open(os.path.join(os.environ["LOG_PATH"], "config-epoch1.json")))
config["routing"]["routeEpoch"] = 2
config["limits"]["largeResponseBytes"] = 64
config["analysis"]["hotKey"]["maxTrackedKeys"] = 3
config["analysis"]["hotKey"]["metricsTopN"] = 2
config["analysis"]["largeKey"]["requestBytesThreshold"] = 64
config["analysis"]["largeKey"]["responseBytesThreshold"] = 64
print(json.dumps({"operator": "e2e", "reason": "observability config switch", "config": config}))
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
      nodes: ["127.0.0.1:63820"]
      pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
routing: {defaultCluster: "redis-a", routeEpoch: 1}
controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 5, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600, largeResponseBytes: 100000}
analysis:
  hotKey: {enabled: true, windowSeconds: 60, bucketMillis: 1000, maxTrackedKeys: 1, metricsTopN: 1}
  largeKey: {enabled: true, requestBytesThreshold: 100000, responseBytesThreshold: 100000, windowSeconds: 300, bucketMillis: 1000, maxTrackedKeys: 10000, debugTopN: 100}
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
        nodes: ["127.0.0.1:63820"]
        pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
  routing: {defaultCluster: "redis-a", routeEpoch: 1}
  controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 5, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
  limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600, largeResponseBytes: 100000}
  analysis:
    hotKey: {enabled: true, windowSeconds: 60, bucketMillis: 1000, maxTrackedKeys: 1, metricsTopN: 1}
    largeKey: {enabled: true, requestBytesThreshold: 100000, responseBytesThreshold: 100000, windowSeconds: 300, bucketMillis: 1000, maxTrackedKeys: 10000, debugTopN: 100}
YAML
  (cd "${ROOT}/redis-proxy-dataplane-java" && mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run -Dspring-boot.run.arguments=--spring.config.location="${LOG_DIR}/proxy.yml" >"${LOG_DIR}/proxy.log" 2>&1) &
fi
PROXY_PID=$!

for i in {1..90}; do
  curl -fsS http://127.0.0.1:8080/readyz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${PROXY_PID}" >/dev/null 2>&1 || { tail -160 "${LOG_DIR}/proxy.log"; exit 1; }
done

proxy_exchange() {
  python3 - "$@" <<'PY'
import socket
import sys

def bulk(value):
    if isinstance(value, str):
        value = value.encode()
    return b"$" + str(len(value)).encode() + b"\r\n" + value + b"\r\n"

def cmd(*values):
    return b"*" + str(len(values)).encode() + b"\r\n" + b"".join(bulk(v) for v in values)

with socket.create_connection(("127.0.0.1", 6379), timeout=5) as sock:
    sock.sendall(b"".join(cmd("GET", key) for key in sys.argv[1:]))
    sock.settimeout(5)
    data = b""
    while data.count(b"\r\n") < len(sys.argv[1:]) * 2:
        chunk = sock.recv(4096)
        if not chunk:
            break
        data += chunk
    sys.stdout.buffer.write(data)
PY
}

metrics_url() {
  if curl -fsS http://127.0.0.1:8080/metrics >/dev/null 2>&1; then
    echo "http://127.0.0.1:8080/metrics"
  else
    echo "http://127.0.0.1:8080/actuator/prometheus"
  fi
}

large_response_count() {
  local file="$1"
  python3 - "$file" <<'PY'
import re
import sys
total = 0.0
for line in open(sys.argv[1]):
    if line.startswith("redis_proxy_large_response_total") and 'command="GET"' in line:
        total += float(line.rsplit(" ", 1)[1])
print(int(total))
PY
}

proxy_exchange obs:k1 obs:k2 >/dev/null
curl -fsS "http://127.0.0.1:8080/debug/hot-keys?limit=10" >"${LOG_DIR}/hot-keys-epoch1.json"
python3 - <<'PY' "${LOG_DIR}/hot-keys-epoch1.json"
import json
import sys
items = json.load(open(sys.argv[1]))
keys = {item.get("key") for item in items}
if keys != {"obs:k1"}:
    raise SystemExit(f"epoch1 hot key capacity was not enforced: {items!r}")
PY

proxy_exchange obs:big >/dev/null
curl -fsS "$(metrics_url)" >"${LOG_DIR}/metrics-epoch1.prom"
if [[ "$(large_response_count "${LOG_DIR}/metrics-epoch1.prom")" != "0" ]]; then
  echo "large response unexpectedly counted before threshold update" >&2
  exit 1
fi

curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/publish-epoch2.json" http://127.0.0.1:8090/api/v1/config/publish >/dev/null
for i in {1..50}; do
  epoch="$(curl -fsS http://127.0.0.1:8080/debug/route-snapshot | python3 -c 'import json,sys; print(json.load(sys.stdin)["epoch"])')"
  [[ "${epoch}" == "2" ]] && break
  sleep 0.2
done
[[ "${epoch}" == "2" ]] || { curl -fsS http://127.0.0.1:8080/debug/route-snapshot; exit 1; }

sleep 1.2
proxy_exchange obs:k1 obs:k2 >/dev/null
sleep 1.2
proxy_exchange obs:big obs:k1 >/dev/null
curl -fsS "http://127.0.0.1:8080/debug/hot-keys?limit=10" >"${LOG_DIR}/hot-keys-epoch2.json"
python3 - <<'PY' "${LOG_DIR}/hot-keys-epoch2.json"
import json
import sys
items = json.load(open(sys.argv[1]))
keys = {item.get("key") for item in items}
expected = {"obs:k1", "obs:k2", "obs:big"}
if not expected.issubset(keys):
    raise SystemExit(f"epoch2 hot key capacity update did not take effect: {items!r}")
PY

curl -fsS "http://127.0.0.1:8080/debug/large-keys?limit=10" >"${LOG_DIR}/large-keys-epoch2.json"
python3 - <<'PY' "${LOG_DIR}/large-keys-epoch2.json"
import json
import sys
items = json.load(open(sys.argv[1]))
matches = [item for item in items if item.get("key") == "obs:big" and item.get("maxResponseBytes", 0) >= 128]
if not matches:
    raise SystemExit(f"large key debug endpoint did not include obs:big response attribution: {items!r}")
PY

curl -fsS "$(metrics_url)" >"${LOG_DIR}/metrics-epoch2.prom"
if [[ "$(large_response_count "${LOG_DIR}/metrics-epoch2.prom")" -lt 1 ]]; then
  echo "large response was not counted after threshold update" >&2
  exit 1
fi
if ! rg 'redis_proxy_large_response_threshold_bytes 64' "${LOG_DIR}/metrics-epoch2.prom" >/dev/null; then
  echo "large response threshold gauge did not update to 64" >&2
  exit 1
fi
if ! rg 'redis_proxy_hot_key_topk_count\{.*rank="2"' "${LOG_DIR}/metrics-epoch2.prom" >/dev/null; then
  echo "hot key metricsTopN update did not expose rank=2" >&2
  exit 1
fi
if ! rg 'redis_proxy_large_key_observed_total\{.*command="GET".*direction="response"' "${LOG_DIR}/metrics-epoch2.prom" >/dev/null; then
  echo "large key response observation metric was not exposed" >&2
  exit 1
fi
if rg '^redis_proxy_large_key_.*obs:big' "${LOG_DIR}/metrics-epoch2.prom" >/dev/null; then
  echo "large key metrics leaked concrete key label" >&2
  exit 1
fi

echo "observability config e2e passed for ${DATAPLANE}"
