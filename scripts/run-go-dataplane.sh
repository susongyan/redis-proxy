#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-standalone}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="${ROOT}/redis-proxy-dataplane-go/configs/${MODE}.yaml"

if [[ ! -f "${CONFIG}" ]]; then
  echo "Unknown mode '${MODE}'. Expected standalone or cluster." >&2
  exit 1
fi

cd "${ROOT}/redis-proxy-dataplane-go"
exec go run ./cmd/proxy -config "${CONFIG}"
