#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-standalone}"
GC_PROFILE="${2:-g1}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "${MODE}" in
  standalone|cluster|cluster-local) ;;
  *)
    echo "Unknown mode '${MODE}'. Expected standalone or cluster." >&2
    exit 1
    ;;
esac

case "${GC_PROFILE}" in
  g1)
    JVM_ARGS="-XX:+UseG1GC -Xlog:gc*:file=target/gc-g1.log:time,uptime,level,tags"
    ;;
  zgc)
    JVM_ARGS="-XX:+UseZGC -Xlog:gc*:file=target/gc-zgc.log:time,uptime,level,tags"
    ;;
  *)
    echo "Unknown GC profile '${GC_PROFILE}'. Expected g1 or zgc." >&2
    exit 1
    ;;
esac

cd "${ROOT}/redis-proxy-dataplane-java"
exec mvn spring-boot:run \
  -Dspring-boot.run.profiles="${MODE}" \
  -Dspring-boot.run.jvmArguments="${JVM_ARGS}"
