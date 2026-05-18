#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME="${REDIS_STANDALONE_CONTAINER:-redis-proxy-standalone}"

if docker ps -a --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
  docker rm -f "${CONTAINER_NAME}" >/dev/null
fi

echo "Redis standalone stopped"
