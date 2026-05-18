#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REDIS_HOST="${REDIS_HOST:-host.docker.internal}"
REDIS_PORT="${REDIS_PORT:-63790}"
STAMP="$(date +%Y%m%d-%H%M%S)"

export PROXY_HOST="${REDIS_HOST}"
export PROXY_PORT="${REDIS_PORT}"
export RUN_NAME="${RUN_NAME:-redis-direct-standalone-${STAMP}}"
export RUN_GROUP="${RUN_GROUP:-direct backend}"
export BACKEND_MODEL="${BACKEND_MODEL:-direct redis}"
export DATAPLANE="${DATAPLANE:-redis}"
export RESULT_DIR="${RESULT_DIR:-bench-results/${RUN_NAME}}"

exec "${ROOT}/scripts/bench.sh"
