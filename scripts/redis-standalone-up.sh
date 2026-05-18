#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME="${REDIS_STANDALONE_CONTAINER:-redis-proxy-standalone}"
PORT="${REDIS_STANDALONE_PORT:-63790}"
IMAGE="${REDIS_IMAGE:-redis:7}"

if docker ps -a --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
  docker rm -f "${CONTAINER_NAME}" >/dev/null
fi

docker run -d \
  --name "${CONTAINER_NAME}" \
  -p "${PORT}:6379" \
  "${IMAGE}" \
  redis-server --appendonly no --save ""

echo "Redis standalone is listening on 127.0.0.1:${PORT}"
