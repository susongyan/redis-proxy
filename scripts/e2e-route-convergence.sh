#!/usr/bin/env bash
set -euo pipefail

DATAPLANE="${1:-go}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/redis-proxy-e2e-route-convergence-${DATAPLANE}}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"

case "${DATAPLANE}" in
  go|java) ;;
  *) echo "Usage: $0 go|java" >&2; exit 1 ;;
esac

rm -rf "${LOG_DIR}" && mkdir -p "${LOG_DIR}"

cleanup() {
  [[ -n "${PROXY_PID:-}" ]] && kill "${PROXY_PID}" >/dev/null 2>&1 || true
  [[ -n "${CP_PID:-}" ]] && kill "${CP_PID}" >/dev/null 2>&1 || true
  docker rm -f redis-proxy-converge-a redis-proxy-converge-b >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in 6379 8080 8090 63800 63801; do
  lsof -ti tcp:${port} | xargs kill >/dev/null 2>&1 || true
done

docker run -d --name redis-proxy-converge-a -p 63800:6379 "${REDIS_IMAGE}" >/dev/null
docker run -d --name redis-proxy-converge-b -p 63801:6379 "${REDIS_IMAGE}" >/dev/null

for i in {1..30}; do
  if docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63800 ping >/dev/null 2>&1 &&
     docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63801 ping >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

(cd "${ROOT}/redis-proxy-control-plane-java" && SPRING_DATASOURCE_URL="jdbc:h2:file:${LOG_DIR}/control-plane;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH" mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run -Dspring-boot.run.arguments=--server.port=8090 >"${LOG_DIR}/control-plane.log" 2>&1) &
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
{"operator":"e2e","reason":"route convergence","config":{"server":{"listen":"0.0.0.0:6379"},"admin":{"listen":"0.0.0.0:8080"},"mode":"standalone","backends":{"clusters":[{"name":"redis-a","nodes":["127.0.0.1:63800"],"pool":{"connectionsPerNode":2,"maxInflightPerConnection":128}},{"name":"redis-b","nodes":["127.0.0.1:63801"],"pool":{"connectionsPerNode":2,"maxInflightPerConnection":128}}]},"routing":{"defaultCluster":"redis-a","routeEpoch":2,"clusterSlotsRefreshIntervalSeconds":30,"rules":[{"name":"gray-user-profile","cluster":"redis-b","namespace":"app-a","keyPattern":"user:*:profile","trafficPercent":100}]},"governance":{"enabled":true,"requireAuth":true,"namespaces":[{"name":"app-a","token":"token-a","readOnly":false,"allowedKeyPrefixes":[],"limits":{"maxConnections":0,"maxQps":0,"maxInflight":0}}]},"limits":{"maxPipelineDepth":1024,"maxRequestBytes":10485760,"maxResponseBytes":104857600}}}
JSON

curl -fsS -X PUT -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/config-epoch1.json" http://127.0.0.1:8090/api/v1/config >/dev/null

if [[ "${DATAPLANE}" == "go" ]]; then
  PROXY_ID="proxy-go-1"
  cat >"${LOG_DIR}/proxy.yaml" <<YAML
instance: {proxyId: "${PROXY_ID}"}
server: {listen: "0.0.0.0:6379"}
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
controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 1, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600}
YAML
  (cd "${ROOT}/redis-proxy-dataplane-go" && go run ./cmd/proxy -config "${LOG_DIR}/proxy.yaml" >"${LOG_DIR}/proxy.log" 2>&1) &
else
  PROXY_ID="proxy-java-1"
  cat >"${LOG_DIR}/proxy.yml" <<YAML
server:
  port: 8080
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
proxy:
  instance: {proxyId: "${PROXY_ID}"}
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
  controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 1, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
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

cat >"${LOG_DIR}/target.json" <<JSON
{"proxyId":"${PROXY_ID}","adminUrl":"http://127.0.0.1:8080","dataplane":"${DATAPLANE}","cluster":"redis-a","pollIntervalSeconds":1}
JSON
curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/target.json" http://127.0.0.1:8090/api/v1/observability/targets >/dev/null

curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/publish-epoch2.json" http://127.0.0.1:8090/api/v1/config/publish >/dev/null
expected_hash="$(curl -fsS http://127.0.0.1:8090/api/v1/routes/status | python3 -c 'import json,sys; print(json.load(sys.stdin)["expectedConfigHash"])')"

for i in {1..50}; do
  snapshot="$(curl -fsS http://127.0.0.1:8080/debug/route-snapshot)"
  epoch="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["epoch"])' <<<"${snapshot}")"
  hash="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["configHash"])' <<<"${snapshot}")"
  proxy_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["proxyId"])' <<<"${snapshot}")"
  [[ "${epoch}" == "2" && "${hash}" == "${expected_hash}" && "${proxy_id}" == "${PROXY_ID}" ]] && break
  sleep 0.2
done
[[ "${epoch}" == "2" && "${hash}" == "${expected_hash}" && "${proxy_id}" == "${PROXY_ID}" ]] || { echo "${snapshot}"; exit 1; }

for i in {1..30}; do
  convergence="$(curl -fsS http://127.0.0.1:8090/api/v1/routes/convergence)"
  status="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' <<<"${convergence}")"
  [[ "${status}" == "CONVERGED" ]] && break
  sleep 1
done
[[ "${status}" == "CONVERGED" ]] || { echo "${convergence}"; exit 1; }
EXPECTED_HASH="${expected_hash}" PROXY_ID="${PROXY_ID}" python3 -c 'import json,os,sys
data = json.load(sys.stdin)
assert data["expectedConfigHash"] == os.environ["EXPECTED_HASH"], data
matches = [proxy for proxy in data["proxies"] if proxy["proxyId"] == os.environ["PROXY_ID"]]
assert matches, data
assert matches[0]["status"] == "CONVERGED", data
' <<<"${convergence}"

echo "route convergence e2e passed for ${DATAPLANE}"
