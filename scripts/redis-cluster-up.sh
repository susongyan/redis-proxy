#!/usr/bin/env bash
set -euo pipefail

IMAGE="${REDIS_IMAGE:-redis:7}"
NETWORK="${REDIS_CLUSTER_NETWORK:-redis-proxy-cluster}"
PORTS="${REDIS_CLUSTER_PORTS:-7000 7001 7002 7003 7004 7005}"
read -r -a NODES <<< "${PORTS}"

if ! docker network inspect "${NETWORK}" >/dev/null 2>&1; then
  docker network create "${NETWORK}" >/dev/null
fi

for port in "${NODES[@]}"; do
  name="redis-proxy-cluster-${port}"
  if docker ps -a --format '{{.Names}}' | grep -qx "${name}"; then
    docker rm -f "${name}" >/dev/null
  fi
  docker run -d \
    --name "${name}" \
    --network "${NETWORK}" \
    -p "${port}:${port}" \
    -p "$((port + 10000)):$((port + 10000))" \
    "${IMAGE}" \
    redis-server \
      --port "${port}" \
      --cluster-enabled yes \
      --cluster-config-file "nodes-${port}.conf" \
      --cluster-node-timeout 5000 \
      --appendonly no \
      --save "" >/dev/null
done

sleep 2

cluster_nodes=()
for port in "${NODES[@]}"; do
  cluster_nodes+=("redis-proxy-cluster-${port}:${port}")
done

docker run --rm -i --network "${NETWORK}" "${IMAGE}" \
  redis-cli --cluster create "${cluster_nodes[@]}" --cluster-replicas 1 --cluster-yes

echo "Redis Cluster is listening on 127.0.0.1:${PORTS}"
