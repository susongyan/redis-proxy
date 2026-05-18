#!/usr/bin/env bash
set -euo pipefail

PROXY_HOST="${PROXY_HOST:-host.docker.internal}"
PROXY_PORT="${PROXY_PORT:-6379}"
IMAGE="${REDIS_IMAGE:-redis:7}"
RESULT_DIR="${RESULT_DIR:-bench-results/$(date +%Y%m%d-%H%M%S)}"
RUN_NAME="${RUN_NAME:-$(basename "${RESULT_DIR}")}"
RUN_GROUP="${RUN_GROUP:-unspecified}"
BACKEND_MODEL="${BACKEND_MODEL:-unspecified}"
DATAPLANE="${DATAPLANE:-unspecified}"
BENCH_PROFILE="${BENCH_PROFILE:-custom}"
BENCH_TARGET_PID="${BENCH_TARGET_PID:-}"
BENCH_TARGET_LABEL="${BENCH_TARGET_LABEL:-}"
BENCH_ADMIN_URL="${BENCH_ADMIN_URL:-}"
BENCH_JAVA_GC_LOG="${BENCH_JAVA_GC_LOG:-}"
RESOURCE_SAMPLE_INTERVAL_SECONDS="${RESOURCE_SAMPLE_INTERVAL_SECONDS:-1}"
REQUESTS="${REQUESTS:-100000}"
CLIENTS_LIST="${CLIENTS_LIST:-50 200}"
PIPELINE_LIST="${PIPELINE_LIST:-1 10 100}"
TESTS="${TESTS:-set,get}"
VALUE_SIZE="${VALUE_SIZE:-3}"
KEYSPACE_LEN="${KEYSPACE_LEN:-}"
BENCHMARK_THREADS="${BENCHMARK_THREADS:-}"

mkdir -p "${RESULT_DIR}"

cat > "${RESULT_DIR}/metadata.txt" <<EOF_META
timestamp=$(date -Iseconds)
run_name=${RUN_NAME}
run_group=${RUN_GROUP}
backend_model=${BACKEND_MODEL}
dataplane=${DATAPLANE}
bench_profile=${BENCH_PROFILE}
bench_target_pid=${BENCH_TARGET_PID}
bench_target_label=${BENCH_TARGET_LABEL}
bench_admin_url=${BENCH_ADMIN_URL}
bench_java_gc_log=${BENCH_JAVA_GC_LOG}
resource_sample_interval_seconds=${RESOURCE_SAMPLE_INTERVAL_SECONDS}
proxy_host=${PROXY_HOST}
proxy_port=${PROXY_PORT}
requests=${REQUESTS}
clients_list=${CLIENTS_LIST}
pipeline_list=${PIPELINE_LIST}
tests=${TESTS}
value_size=${VALUE_SIZE}
keyspace_len=${KEYSPACE_LEN}
benchmark_threads=${BENCHMARK_THREADS}
EOF_META

sample_resources() {
  local output="$1"
  if [[ -z "${BENCH_TARGET_PID}" ]]; then
    return 0
  fi
  echo "timestamp,pid,cpu_percent,rss_kb,vsz_kb,threads" > "${output}"
  while kill -0 "${BENCH_TARGET_PID}" >/dev/null 2>&1; do
    local row threads
    row="$(ps -p "${BENCH_TARGET_PID}" -o pid=,pcpu=,rss=,vsz= 2>/dev/null | awk '{$1=$1; print}')"
    if [[ -n "${row}" ]]; then
      # shellcheck disable=SC2086
      set -- ${row}
      threads="$(ps -M -p "${BENCH_TARGET_PID}" 2>/dev/null | awk 'NR > 1 {count++} END {print count + 0}')"
      if [[ "${threads}" == "0" ]] && ps -L -p "${BENCH_TARGET_PID}" >/dev/null 2>&1; then
        threads="$(ps -L -p "${BENCH_TARGET_PID}" 2>/dev/null | awk 'NR > 1 {count++} END {print count + 0}')"
      fi
      echo "$(date -Iseconds),$1,$2,$3,$4,${threads}" >> "${output}"
    fi
    sleep "${RESOURCE_SAMPLE_INTERVAL_SECONDS}"
  done
}

capture_metrics() {
  local output="$1"
  if [[ -z "${BENCH_ADMIN_URL}" ]]; then
    return 0
  fi
  if curl -fsS "${BENCH_ADMIN_URL}/metrics" -o "${output}" 2>/dev/null; then
    return 0
  fi
  if curl -fsS "${BENCH_ADMIN_URL}/actuator/prometheus" -o "${output}" 2>/dev/null; then
    return 0
  fi
  echo "# metrics unavailable from ${BENCH_ADMIN_URL}" > "${output}"
}

for clients in ${CLIENTS_LIST}; do
  for pipeline in ${PIPELINE_LIST}; do
    output="${RESULT_DIR}/redis-benchmark-c${clients}-p${pipeline}.txt"
    resource_output="${RESULT_DIR}/resource-c${clients}-p${pipeline}.csv"
    capture_metrics "${RESULT_DIR}/metrics-before-c${clients}-p${pipeline}.prom"
    echo "Running redis-benchmark clients=${clients} pipeline=${pipeline}"
    if [[ -n "${BENCH_TARGET_PID}" ]]; then
      sample_resources "${resource_output}" &
      sampler_pid="$!"
    else
      sampler_pid=""
    fi
    benchmark_args=(
      -h "${PROXY_HOST}"
      -p "${PROXY_PORT}"
      -n "${REQUESTS}"
      -c "${clients}"
      -P "${pipeline}"
      -d "${VALUE_SIZE}"
      -t "${TESTS}"
      --csv
    )
    if [[ -n "${KEYSPACE_LEN}" ]]; then
      benchmark_args+=(-r "${KEYSPACE_LEN}")
    fi
    if [[ -n "${BENCHMARK_THREADS}" ]]; then
      benchmark_args+=(--threads "${BENCHMARK_THREADS}")
    fi
    set +e
    docker run --rm "${IMAGE}" redis-benchmark "${benchmark_args[@]}" | tee "${output}"
    benchmark_status="${PIPESTATUS[0]}"
    set -e
    if [[ -n "${sampler_pid}" ]]; then
      kill "${sampler_pid}" >/dev/null 2>&1 || true
      wait "${sampler_pid}" >/dev/null 2>&1 || true
    fi
    capture_metrics "${RESULT_DIR}/metrics-after-c${clients}-p${pipeline}.prom"
    if [[ "${benchmark_status}" -ne 0 ]]; then
      exit "${benchmark_status}"
    fi
  done
done

if [[ -n "${BENCH_JAVA_GC_LOG}" && -f "${BENCH_JAVA_GC_LOG}" ]]; then
  cp "${BENCH_JAVA_GC_LOG}" "${RESULT_DIR}/$(basename "${BENCH_JAVA_GC_LOG}")"
fi

python3 "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/summarize-resource-metrics.py" "${RESULT_DIR}" >/dev/null

echo "Benchmark results written to ${RESULT_DIR}"
