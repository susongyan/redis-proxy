#!/usr/bin/env bash
set -euo pipefail

PROXY_HOST="${PROXY_HOST:-host.docker.internal}"
PROXY_PORT="${PROXY_PORT:-6379}"
IMAGE="${REDIS_IMAGE:-redis:7}"
RESULT_DIR="${RESULT_DIR:-bench-results/$(date +%Y%m%d-%H%M%S)}"
REQUESTS="${REQUESTS:-100000}"
CLIENTS_LIST="${CLIENTS_LIST:-50 200}"
PIPELINE_LIST="${PIPELINE_LIST:-1 10 100}"
TESTS="${TESTS:-set,get}"

mkdir -p "${RESULT_DIR}"

cat > "${RESULT_DIR}/metadata.txt" <<EOF_META
timestamp=$(date -Iseconds)
proxy_host=${PROXY_HOST}
proxy_port=${PROXY_PORT}
requests=${REQUESTS}
clients_list=${CLIENTS_LIST}
pipeline_list=${PIPELINE_LIST}
tests=${TESTS}
EOF_META

for clients in ${CLIENTS_LIST}; do
  for pipeline in ${PIPELINE_LIST}; do
    output="${RESULT_DIR}/redis-benchmark-c${clients}-p${pipeline}.txt"
    echo "Running redis-benchmark clients=${clients} pipeline=${pipeline}"
    docker run --rm "${IMAGE}" redis-benchmark \
      -h "${PROXY_HOST}" \
      -p "${PROXY_PORT}" \
      -n "${REQUESTS}" \
      -c "${clients}" \
      -P "${pipeline}" \
      -t "${TESTS}" \
      --csv | tee "${output}"
  done
done

echo "Benchmark results written to ${RESULT_DIR}"
