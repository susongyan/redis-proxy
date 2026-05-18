#!/usr/bin/env bash
set -euo pipefail

PROXY_HOST="${PROXY_HOST:-host.docker.internal}"
PROXY_PORT="${PROXY_PORT:-6379}"
ADMIN_URL="${ADMIN_URL:-http://127.0.0.1:8080}"
IMAGE="${REDIS_IMAGE:-redis:7}"
KEY_PREFIX="${KEY_PREFIX:-redis-proxy-smoke}"

redis_cli() {
  docker run --rm "${IMAGE}" redis-cli -h "${PROXY_HOST}" -p "${PROXY_PORT}" "$@"
}

expect_eq() {
  local label="$1"
  local actual="$2"
  local expected="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "FAIL ${label}: expected '${expected}', got '${actual}'" >&2
    exit 1
  fi
  echo "PASS ${label}"
}

expect_contains() {
  local label="$1"
  local actual="$2"
  local needle="$3"
  if [[ "${actual}" != *"${needle}"* ]]; then
    echo "FAIL ${label}: expected output to contain '${needle}', got '${actual}'" >&2
    exit 1
  fi
  echo "PASS ${label}"
}

expect_eq "PING" "$(redis_cli PING | tr -d '\r')" "PONG"
expect_eq "SET" "$(redis_cli SET "${KEY_PREFIX}:basic" "ok" | tr -d '\r')" "OK"
expect_eq "GET" "$(redis_cli GET "${KEY_PREFIX}:basic" | tr -d '\r')" "ok"
expect_eq "DEL" "$(redis_cli DEL "${KEY_PREFIX}:basic" | tr -d '\r')" "1"

pipe_key="${KEY_PREFIX}:pipe"
PIPE_HOST="${PROXY_HOST}" PIPE_PORT="${PROXY_PORT}" PIPE_KEY="${pipe_key}" python3 - <<'PY'
import os
import socket

host = os.environ["PIPE_HOST"]
if host == "host.docker.internal":
    host = "127.0.0.1"
port = int(os.environ["PIPE_PORT"])
key = os.environ["PIPE_KEY"].encode()

def bulk(value: bytes) -> bytes:
    return b"$" + str(len(value)).encode() + b"\r\n" + value + b"\r\n"

payload = (
    b"*3\r\n" + bulk(b"SET") + bulk(key) + bulk(b"one") +
    b"*2\r\n" + bulk(b"GET") + bulk(key) +
    b"*2\r\n" + bulk(b"DEL") + bulk(key)
)

with socket.create_connection((host, port), timeout=5) as sock:
    sock.sendall(payload)
    data = b""
    while len(data) < len(b"+OK\r\n$3\r\none\r\n:1\r\n"):
        chunk = sock.recv(4096)
        if not chunk:
            break
        data += chunk

expected = b"+OK\r\n$3\r\none\r\n:1\r\n"
if data != expected:
    raise SystemExit(f"unexpected pipeline response: {data!r}")
PY
echo "PASS pipeline"

big_value="$(python3 - <<'PY'
print("x" * 65536)
PY
)"
expect_eq "large SET" "$(redis_cli SET "${KEY_PREFIX}:large" "${big_value}" | tr -d '\r')" "OK"
large_len="$(redis_cli STRLEN "${KEY_PREFIX}:large" | tr -d '\r')"
expect_eq "large GET length" "${large_len}" "65536"
redis_cli DEL "${KEY_PREFIX}:large" >/dev/null

health="$(curl -fsS "${ADMIN_URL}/healthz" | tr -d '\r')"
expect_contains "healthz" "${health}" "ok"

if curl -fsS "${ADMIN_URL}/metrics" >/dev/null 2>&1 || curl -fsS "${ADMIN_URL}/actuator/prometheus" >/dev/null 2>&1; then
  echo "PASS metrics"
else
  echo "FAIL metrics: neither /metrics nor /actuator/prometheus is reachable" >&2
  exit 1
fi

echo "Smoke test passed for ${PROXY_HOST}:${PROXY_PORT}"
