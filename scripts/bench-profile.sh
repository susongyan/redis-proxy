#!/usr/bin/env bash
set -euo pipefail

PROFILE="${1:-baseline}"
TARGET="${2:-proxy}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"

case "${PROFILE}" in
  smoke)
    export REQUESTS="${REQUESTS:-1000}"
    export CLIENTS_LIST="${CLIENTS_LIST:-10}"
    export PIPELINE_LIST="${PIPELINE_LIST:-1}"
    export TESTS="${TESTS:-set,get}"
    export RESOURCE_SAMPLE_INTERVAL_SECONDS="${RESOURCE_SAMPLE_INTERVAL_SECONDS:-1}"
    ;;
  baseline)
    export REQUESTS="${REQUESTS:-20000}"
    export CLIENTS_LIST="${CLIENTS_LIST:-50 200}"
    export PIPELINE_LIST="${PIPELINE_LIST:-1 10 100}"
    export TESTS="${TESTS:-set,get}"
    export RESOURCE_SAMPLE_INTERVAL_SECONDS="${RESOURCE_SAMPLE_INTERVAL_SECONDS:-1}"
    ;;
  long)
    export REQUESTS="${REQUESTS:-1000000}"
    export CLIENTS_LIST="${CLIENTS_LIST:-50 200}"
    export PIPELINE_LIST="${PIPELINE_LIST:-1 10 100}"
    export TESTS="${TESTS:-set,get}"
    export RESOURCE_SAMPLE_INTERVAL_SECONDS="${RESOURCE_SAMPLE_INTERVAL_SECONDS:-5}"
    ;;
  *)
    echo "Unknown profile '${PROFILE}'. Expected smoke, baseline, or long." >&2
    exit 1
    ;;
esac

case "${TARGET}" in
  proxy)
    export RUN_GROUP="${RUN_GROUP:-proxy}"
    export BACKEND_MODEL="${BACKEND_MODEL:-unspecified}"
    export DATAPLANE="${DATAPLANE:-unspecified}"
    export PROXY_HOST="${PROXY_HOST:-host.docker.internal}"
    export PROXY_PORT="${PROXY_PORT:-6379}"
    ;;
  redis-direct)
    export RUN_GROUP="${RUN_GROUP:-direct backend}"
    export BACKEND_MODEL="${BACKEND_MODEL:-direct redis}"
    export DATAPLANE="${DATAPLANE:-redis}"
    export PROXY_HOST="${PROXY_HOST:-host.docker.internal}"
    export PROXY_PORT="${PROXY_PORT:-63790}"
    ;;
  *)
    echo "Unknown target '${TARGET}'. Expected proxy or redis-direct." >&2
    exit 1
    ;;
esac

export BENCH_PROFILE="${PROFILE}"
export RUN_NAME="${RUN_NAME:-${DATAPLANE}-${PROFILE}-${TARGET}-${STAMP}}"
export RESULT_DIR="${RESULT_DIR:-bench-results/${RUN_NAME}}"

exec "${ROOT}/scripts/bench.sh"
