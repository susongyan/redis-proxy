#!/usr/bin/env bash
set -euo pipefail

NETWORK="${REDIS_CLUSTER_NETWORK:-redis-proxy-cluster}"
NODES=(7000 7001 7002 7003 7004 7005)

for port in "${NODES[@]}"; do
  name="redis-proxy-cluster-${port}"
  if docker ps -a --format '{{.Names}}' | grep -qx "${name}"; then
    docker rm -f "${name}" >/dev/null
  fi
done

if docker network inspect "${NETWORK}" >/dev/null 2>&1; then
  docker network rm "${NETWORK}" >/dev/null
fi

echo "Redis Cluster stopped"
