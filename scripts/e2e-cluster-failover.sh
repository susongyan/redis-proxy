#!/usr/bin/env bash
set -euo pipefail

DATAPLANE="${1:-go}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/redis-proxy-e2e-cluster-failover-${DATAPLANE}}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"
PORTS="7100 7101 7102 7103 7104 7105"

case "${DATAPLANE}" in
  go|java) ;;
  *) echo "Usage: $0 go|java" >&2; exit 1 ;;
esac

rm -rf "${LOG_DIR}" && mkdir -p "${LOG_DIR}"

cleanup() {
  [[ -n "${PROXY_PID:-}" ]] && kill "${PROXY_PID}" >/dev/null 2>&1 || true
  REDIS_CLUSTER_PORTS="${PORTS}" "${ROOT}/scripts/redis-cluster-down.sh" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in 6379 8080 7100 7101 7102 7103 7104 7105 17100 17101 17102 17103 17104 17105; do
  lsof -ti tcp:${port} | xargs kill >/dev/null 2>&1 || true
done

REDIS_CLUSTER_PORTS="${PORTS}" "${ROOT}/scripts/redis-cluster-up.sh" >"${LOG_DIR}/cluster-up.log" 2>&1

if [[ "${DATAPLANE}" == "go" ]]; then
  (cd "${ROOT}" && ./scripts/run-go-dataplane.sh cluster-local >"${LOG_DIR}/proxy.log" 2>&1) &
else
  (cd "${ROOT}" && ./scripts/run-java-dataplane.sh cluster-local g1 >"${LOG_DIR}/proxy.log" 2>&1) &
fi
PROXY_PID=$!

for i in {1..120}; do
  curl -fsS http://127.0.0.1:8080/readyz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${PROXY_PID}" >/dev/null 2>&1 || { tail -150 "${LOG_DIR}/proxy.log"; exit 1; }
done

KEY_PREFIX="redis-proxy-failover-${DATAPLANE}" "${ROOT}/scripts/smoke.sh" >"${LOG_DIR}/smoke-before.log" 2>&1

master_port="$(docker exec redis-proxy-cluster-7100 redis-cli -p 7100 cluster nodes | awk '/master/ && !/fail/ {split($2,a,":"); split(a[2],p,"@"); print p[1]; exit}')"
if [[ -z "${master_port}" ]]; then
  echo "could not resolve cluster master port" >&2
  docker exec redis-proxy-cluster-7100 redis-cli -p 7100 cluster nodes >&2 || true
  exit 1
fi
docker rm -f "redis-proxy-cluster-${master_port}" >"${LOG_DIR}/stopped-master.log" 2>&1

for i in {1..150}; do
  if curl -fsS http://127.0.0.1:8080/readyz >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
curl -fsS http://127.0.0.1:8080/readyz >/dev/null

KEY_PREFIX="redis-proxy-failover-${DATAPLANE}-after" "${ROOT}/scripts/smoke.sh" >"${LOG_DIR}/smoke-after.log" 2>&1

echo "cluster failover e2e passed for ${DATAPLANE}, stopped master ${master_port}"
