#!/usr/bin/env bash
set -euo pipefail

DATAPLANE="${1:-go}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/redis-proxy-e2e-dynamic-route-${DATAPLANE}}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"

case "${DATAPLANE}" in
  go|java) ;;
  *) echo "Usage: $0 go|java" >&2; exit 1 ;;
esac

rm -rf "${LOG_DIR}" && mkdir -p "${LOG_DIR}"

cleanup() {
  [[ -n "${PROXY_PID:-}" ]] && kill "${PROXY_PID}" >/dev/null 2>&1 || true
  [[ -n "${CP_PID:-}" ]] && kill "${CP_PID}" >/dev/null 2>&1 || true
  docker rm -f redis-proxy-dynamic-a redis-proxy-dynamic-b >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in 6379 8080 8090 63800 63801; do
  lsof -ti tcp:${port} | xargs kill >/dev/null 2>&1 || true
done

docker run -d --name redis-proxy-dynamic-a -p 63800:6379 "${REDIS_IMAGE}" >/dev/null
docker run -d --name redis-proxy-dynamic-b -p 63801:6379 "${REDIS_IMAGE}" >/dev/null

for i in {1..30}; do
  if docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63800 ping >/dev/null 2>&1 &&
     docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63801 ping >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

(cd "${ROOT}/redis-proxy-control-plane-java" && mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run -Dspring-boot.run.arguments=--server.port=8090 >"${LOG_DIR}/control-plane.log" 2>&1) &
CP_PID=$!
for i in {1..60}; do
  curl -fsS http://127.0.0.1:8090/healthz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${CP_PID}" >/dev/null 2>&1 || { tail -100 "${LOG_DIR}/control-plane.log"; exit 1; }
done

cat >"${LOG_DIR}/config-epoch1.json" <<'JSON'
{"server":{"listen":"0.0.0.0:6379"},"admin":{"listen":"0.0.0.0:8080"},"mode":"standalone","backends":{"clusters":[{"name":"redis-a","nodes":["127.0.0.1:63800"],"pool":{"connectionsPerNode":2,"maxInflightPerConnection":128}},{"name":"redis-b","nodes":["127.0.0.1:63801"],"pool":{"connectionsPerNode":2,"maxInflightPerConnection":128}}]},"routing":{"defaultCluster":"redis-a","routeEpoch":1,"clusterSlotsRefreshIntervalSeconds":30,"rules":[]},"limits":{"maxPipelineDepth":1024,"maxRequestBytes":10485760,"maxResponseBytes":104857600}}
JSON
cat >"${LOG_DIR}/publish-epoch2.json" <<'JSON'
{"operator":"e2e","reason":"dynamic route switch","config":{"server":{"listen":"0.0.0.0:6379"},"admin":{"listen":"0.0.0.0:8080"},"mode":"standalone","backends":{"clusters":[{"name":"redis-a","nodes":["127.0.0.1:63800"],"pool":{"connectionsPerNode":2,"maxInflightPerConnection":128}},{"name":"redis-b","nodes":["127.0.0.1:63801"],"pool":{"connectionsPerNode":2,"maxInflightPerConnection":128}}]},"routing":{"defaultCluster":"redis-a","routeEpoch":2,"clusterSlotsRefreshIntervalSeconds":30,"rules":[{"name":"gray-user","cluster":"redis-b","keyPrefix":"user:","trafficPercent":100}]},"limits":{"maxPipelineDepth":1024,"maxRequestBytes":10485760,"maxResponseBytes":104857600}}}
JSON

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
      nodes: ["127.0.0.1:63800"]
      pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
    - name: "redis-b"
      nodes: ["127.0.0.1:63801"]
      pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
routing:
  defaultCluster: "redis-a"
  routeEpoch: 1
controlPlane:
  enabled: true
  url: "http://127.0.0.1:8090/api/v1/config"
  pollIntervalSeconds: 5
  watchTimeoutSeconds: 30
  requestTimeoutMillis: 1000
limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600}
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
        nodes: ["127.0.0.1:63800"]
        pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
      - name: "redis-b"
        nodes: ["127.0.0.1:63801"]
        pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
  routing: {defaultCluster: "redis-a", routeEpoch: 1}
  controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 5, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
  limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600}
YAML
  (cd "${ROOT}/redis-proxy-dataplane-java" && mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run -Dspring-boot.run.arguments=--spring.config.location="${LOG_DIR}/proxy.yml" >"${LOG_DIR}/proxy.log" 2>&1) &
fi
PROXY_PID=$!

for i in {1..90}; do
  curl -fsS http://127.0.0.1:8080/readyz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${PROXY_PID}" >/dev/null 2>&1 || { tail -120 "${LOG_DIR}/proxy.log"; exit 1; }
done

curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/publish-epoch2.json" http://127.0.0.1:8090/api/v1/config/publish >/dev/null
for i in {1..50}; do
  epoch="$(curl -fsS http://127.0.0.1:8080/debug/route-snapshot | python3 -c 'import json,sys; print(json.load(sys.stdin)["epoch"])')"
  [[ "${epoch}" == "2" ]] && break
  sleep 0.2
done
[[ "${epoch}" == "2" ]] || { curl -fsS http://127.0.0.1:8080/debug/route-snapshot; exit 1; }

docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 6379 set order:1 a >/dev/null
docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 6379 set user:1 b >/dev/null
[[ "$(docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63800 get order:1)" == "a" ]]
[[ "$(docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63801 get user:1)" == "b" ]]

if curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/publish-epoch2.json" http://127.0.0.1:8090/api/v1/config/publish >/dev/null 2>&1; then
  echo "stale publish unexpectedly succeeded" >&2
  exit 1
fi

echo "dynamic route e2e passed for ${DATAPLANE}"
