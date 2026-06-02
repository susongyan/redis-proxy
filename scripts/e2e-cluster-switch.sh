#!/usr/bin/env bash
set -euo pipefail

DATAPLANE="${1:-go}"
MODE="${2:-staged}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/redis-proxy-e2e-cluster-switch-${DATAPLANE}-${MODE}}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"

case "${DATAPLANE}" in
  go|java) ;;
  *) echo "Usage: $0 go|java staged|full" >&2; exit 1 ;;
esac
case "${MODE}" in
  staged|full) ;;
  *) echo "Usage: $0 go|java staged|full" >&2; exit 1 ;;
esac

rm -rf "${LOG_DIR}" && mkdir -p "${LOG_DIR}"

cleanup() {
  [[ -n "${PROXY_PID:-}" ]] && kill "${PROXY_PID}" >/dev/null 2>&1 || true
  [[ -n "${CP_PID:-}" ]] && kill "${CP_PID}" >/dev/null 2>&1 || true
  docker rm -f redis-proxy-switch-a redis-proxy-switch-b >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in 6379 8080 8090 63810 63811; do
  lsof -ti tcp:${port} | xargs kill >/dev/null 2>&1 || true
done

docker run -d --name redis-proxy-switch-a -p 63810:6379 "${REDIS_IMAGE}" >/dev/null
docker run -d --name redis-proxy-switch-b -p 63811:6379 "${REDIS_IMAGE}" >/dev/null

for i in {1..30}; do
  if docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63810 ping >/dev/null 2>&1 &&
     docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63811 ping >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

(cd "${ROOT}/redis-proxy-control-plane-java" && mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8090 --spring.datasource.url=jdbc:h2:file:${LOG_DIR}/control-plane-db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH" \
  >"${LOG_DIR}/control-plane.log" 2>&1) &
CP_PID=$!
for i in {1..60}; do
  curl -fsS http://127.0.0.1:8090/healthz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${CP_PID}" >/dev/null 2>&1 || { tail -120 "${LOG_DIR}/control-plane.log"; exit 1; }
done

cat >"${LOG_DIR}/config-epoch1.json" <<'JSON'
{"server":{"listen":"0.0.0.0:6379"},"admin":{"listen":"0.0.0.0:8080"},"mode":"standalone","backends":{"clusters":[{"name":"redis-a","nodes":["127.0.0.1:63810"],"pool":{"connectionsPerNode":2,"maxInflightPerConnection":128}},{"name":"redis-b","nodes":["127.0.0.1:63811"],"pool":{"connectionsPerNode":2,"maxInflightPerConnection":128}}]},"routing":{"defaultCluster":"redis-a","routeEpoch":1,"clusterSlotsRefreshIntervalSeconds":30,"rules":[]},"limits":{"maxPipelineDepth":1024,"maxRequestBytes":10485760,"maxResponseBytes":104857600}}
JSON
curl -fsS -X PUT -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/config-epoch1.json" http://127.0.0.1:8090/api/v1/config >/dev/null

if [[ "${DATAPLANE}" == "go" ]]; then
  cat >"${LOG_DIR}/proxy.yaml" <<'YAML'
instance: {proxyId: "proxy-go-switch-1"}
server: {listen: "0.0.0.0:6379"}
admin: {listen: "0.0.0.0:8080"}
mode: "standalone"
backends:
  clusters:
    - name: "redis-a"
      nodes: ["127.0.0.1:63810"]
      pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
    - name: "redis-b"
      nodes: ["127.0.0.1:63811"]
      pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
routing: {defaultCluster: "redis-a", routeEpoch: 1, clusterSlotsRefreshIntervalSeconds: 30}
controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 5, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600}
YAML
  (cd "${ROOT}/redis-proxy-dataplane-go" && go run ./cmd/proxy -config "${LOG_DIR}/proxy.yaml" >"${LOG_DIR}/proxy.log" 2>&1) &
  PROXY_ID="proxy-go-switch-1"
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
  instance: {proxyId: "proxy-java-switch-1"}
  server: {listen: "0.0.0.0:6379", bossThreads: 1, workerThreads: 0}
  admin: {listen: "0.0.0.0:8080"}
  mode: "standalone"
  backends:
    clusters:
      - name: "redis-a"
        nodes: ["127.0.0.1:63810"]
        pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
      - name: "redis-b"
        nodes: ["127.0.0.1:63811"]
        pool: {connectionsPerNode: 2, maxInflightPerConnection: 128}
  routing: {defaultCluster: "redis-a", routeEpoch: 1, clusterSlotsRefreshIntervalSeconds: 30}
  controlPlane: {enabled: true, url: "http://127.0.0.1:8090/api/v1/config", pollIntervalSeconds: 5, watchTimeoutSeconds: 30, requestTimeoutMillis: 1000}
  limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600}
YAML
  (cd "${ROOT}/redis-proxy-dataplane-java" && mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run -Dspring-boot.run.arguments=--spring.config.location="${LOG_DIR}/proxy.yml" >"${LOG_DIR}/proxy.log" 2>&1) &
  PROXY_ID="proxy-java-switch-1"
fi
PROXY_PID=$!

for i in {1..90}; do
  curl -fsS http://127.0.0.1:8080/readyz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${PROXY_PID}" >/dev/null 2>&1 || { tail -120 "${LOG_DIR}/proxy.log"; exit 1; }
done

curl -fsS -X POST -H 'Content-Type: application/json' \
  -d "{\"proxyId\":\"${PROXY_ID}\",\"adminUrl\":\"http://127.0.0.1:8080\",\"dataplane\":\"${DATAPLANE}\",\"cluster\":\"local\",\"pollIntervalSeconds\":1}" \
  http://127.0.0.1:8090/api/v1/observability/targets >/dev/null

wait_converged() {
  for i in {1..60}; do
    status="$(curl -fsS http://127.0.0.1:8090/api/v1/routes/convergence | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')"
    [[ "${status}" == "CONVERGED" ]] && return 0
    sleep 1
  done
  curl -fsS http://127.0.0.1:8090/api/v1/routes/convergence
  return 1
}
wait_snapshot_default() {
  local expected="$1"
  for i in {1..60}; do
    default_cluster="$(curl -fsS http://127.0.0.1:8080/debug/route-snapshot | python3 -c 'import json,sys; print(json.load(sys.stdin)["defaultCluster"])')"
    [[ "${default_cluster}" == "${expected}" ]] && return 0
    sleep 0.5
  done
  curl -fsS http://127.0.0.1:8080/debug/route-snapshot
  return 1
}
post_expect_ok() {
  local url="$1"
  local body="${2:-}"
  local response
  if [[ -n "${body}" ]]; then
    response="$(curl -sS -w $'\n%{http_code}' -X POST -H 'Content-Type: application/json' -d "${body}" "${url}")"
  else
    response="$(curl -sS -w $'\n%{http_code}' -X POST "${url}")"
  fi
  local code="${response##*$'\n'}"
  local payload="${response%$'\n'*}"
  if [[ "${code}" != "200" ]]; then
    echo "POST ${url} failed with ${code}: ${payload}" >&2
    exit 1
  fi
  printf '%s' "${payload}"
}

wait_converged
docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 6379 set switch:before source >/dev/null
[[ "$(docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63810 get switch:before)" == "source" ]]

MODE_UPPER="$(printf '%s' "${MODE}" | tr '[:lower:]' '[:upper:]')"
cat >"${LOG_DIR}/create-plan.json" <<JSON
{"sourceCluster":"redis-a","targetCluster":"redis-b","mode":"${MODE_UPPER}","operator":"e2e","reason":"cluster switch e2e"}
JSON
plan_id="$(curl -fsS -X POST -H 'Content-Type: application/json' --data-binary @"${LOG_DIR}/create-plan.json" http://127.0.0.1:8090/api/v1/cluster-switch/plans | python3 -c 'import json,sys; print(json.load(sys.stdin)["planId"])')"
post_expect_ok "http://127.0.0.1:8090/api/v1/cluster-switch/plans/${plan_id}/precheck" >/dev/null
post_expect_ok "http://127.0.0.1:8090/api/v1/cluster-switch/plans/${plan_id}/start" >/dev/null

if [[ "${MODE}" == "staged" ]]; then
  wait_converged
  post_expect_ok "http://127.0.0.1:8090/api/v1/cluster-switch/plans/${plan_id}/advance" >/dev/null
  wait_converged
  post_expect_ok "http://127.0.0.1:8090/api/v1/cluster-switch/plans/${plan_id}/jump" '{"trafficPercent":100,"operator":"e2e","reason":"complete switch"}' >/dev/null
fi

wait_snapshot_default "redis-b"
wait_converged
docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 6379 set switch:after target >/dev/null
[[ "$(docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63811 get switch:after)" == "target" ]]

post_expect_ok "http://127.0.0.1:8090/api/v1/cluster-switch/plans/${plan_id}/rollback" '{"operator":"e2e","reason":"rollback switch"}' >/dev/null
wait_snapshot_default "redis-a"
docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 6379 set switch:rollback source >/dev/null
[[ "$(docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 63810 get switch:rollback)" == "source" ]]

echo "cluster switch ${MODE} e2e passed for ${DATAPLANE}"
