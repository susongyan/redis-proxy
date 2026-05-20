#!/usr/bin/env bash
set -euo pipefail

DATAPLANE="${1:-go}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-/tmp/redis-proxy-e2e-asking-${DATAPLANE}}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"

case "${DATAPLANE}" in
  go|java) ;;
  *) echo "Usage: $0 go|java" >&2; exit 1 ;;
esac

rm -rf "${LOG_DIR}" && mkdir -p "${LOG_DIR}"

cleanup() {
  [[ -n "${PROXY_PID:-}" ]] && kill "${PROXY_PID}" >/dev/null 2>&1 || true
  [[ -n "${FAKE_PID:-}" ]] && kill "${FAKE_PID}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for port in 6379 8080 63910 63911; do
  lsof -ti tcp:${port} | xargs kill >/dev/null 2>&1 || true
done

python3 - <<'PY' >"${LOG_DIR}/fake-redis.log" 2>&1 &
import socket, threading

def read_line(conn):
    data=b''
    while not data.endswith(b'\r\n'):
        chunk=conn.recv(1)
        if not chunk:
            raise EOFError
        data += chunk
    return data[:-2]

def read_array(conn):
    first=read_line(conn)
    if not first.startswith(b'*'):
        raise ValueError(first)
    args=[]
    for _ in range(int(first[1:])):
        bulk=read_line(conn)
        size=int(bulk[1:])
        data=b''
        while len(data) < size + 2:
            chunk=conn.recv(size + 2 - len(data))
            if not chunk:
                raise EOFError
            data += chunk
        args.append(data[:size])
    return args

def serve(port, handler):
    server=socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('127.0.0.1', port))
    server.listen()
    while True:
        conn,_=server.accept()
        threading.Thread(target=handler,args=(conn,),daemon=True).start()

def default_handler(conn):
    with conn:
        while True:
            try:
                read_array(conn)
                conn.sendall(b'-ASK 42 127.0.0.1:63911\r\n')
            except Exception:
                return

def target_handler(conn):
    asking=False
    with conn:
        while True:
            try:
                args=read_array(conn)
                cmd=args[0].upper() if args else b''
                if cmd == b'ASKING':
                    asking=True
                    conn.sendall(b'+OK\r\n')
                elif asking:
                    asking=False
                    conn.sendall(b'$3\r\nbar\r\n' if cmd == b'GET' else b'+OK\r\n')
                else:
                    conn.sendall(b'-ERR expected ASKING\r\n')
            except Exception:
                return

threading.Thread(target=serve,args=(63910, default_handler),daemon=True).start()
threading.Thread(target=serve,args=(63911, target_handler),daemon=True).start()
threading.Event().wait()
PY
FAKE_PID=$!

for i in {1..30}; do
  if lsof -nP -iTCP:63910 -sTCP:LISTEN >/dev/null 2>&1 &&
     lsof -nP -iTCP:63911 -sTCP:LISTEN >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done

if [[ "${DATAPLANE}" == "go" ]]; then
  cat >"${LOG_DIR}/proxy.yaml" <<'YAML'
server:
  listen: "0.0.0.0:6379"
admin:
  listen: "0.0.0.0:8080"
mode: "standalone"
backends:
  clusters:
    - name: "redis-a"
      nodes: ["127.0.0.1:63910"]
      pool: {connectionsPerNode: 1, maxInflightPerConnection: 64}
routing: {defaultCluster: "redis-a", routeEpoch: 1}
limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600}
YAML
  (cd "${ROOT}/redis-proxy-dataplane-go" && go run ./cmd/proxy -config "${LOG_DIR}/proxy.yaml" >"${LOG_DIR}/proxy.log" 2>&1) &
else
  cat >"${LOG_DIR}/proxy.yml" <<'YAML'
server:
  port: 8080
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
proxy:
  server: {listen: "0.0.0.0:6379", bossThreads: 1, workerThreads: 0}
  admin: {listen: "0.0.0.0:8080"}
  mode: "standalone"
  backends:
    clusters:
      - name: "redis-a"
        nodes: ["127.0.0.1:63910"]
        pool: {connectionsPerNode: 1, maxInflightPerConnection: 64}
  routing: {defaultCluster: "redis-a", routeEpoch: 1}
  limits: {maxPipelineDepth: 1024, maxRequestBytes: 10485760, maxResponseBytes: 104857600}
YAML
  (cd "${ROOT}/redis-proxy-dataplane-java" && mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 spring-boot:run -Dspring-boot.run.arguments=--spring.config.location="${LOG_DIR}/proxy.yml" >"${LOG_DIR}/proxy.log" 2>&1) &
fi
PROXY_PID=$!

for i in {1..90}; do
  curl -fsS http://127.0.0.1:8080/readyz >/dev/null 2>&1 && break
  sleep 1
  kill -0 "${PROXY_PID}" >/dev/null 2>&1 || { tail -120 "${LOG_DIR}/proxy.log"; exit 1; }
done

[[ "$(docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 6379 set foo bar)" == "OK" ]]
[[ "$(docker run --rm "${REDIS_IMAGE}" redis-cli -h host.docker.internal -p 6379 get foo)" == "bar" ]]

if [[ "${DATAPLANE}" == "go" ]]; then
  curl -fsS http://127.0.0.1:8080/metrics >"${LOG_DIR}/metrics.prom"
  rg 'redis_proxy_ask_redirect_total\{result="success"\}' "${LOG_DIR}/metrics.prom" >/dev/null
else
  curl -fsS http://127.0.0.1:8080/actuator/prometheus >"${LOG_DIR}/metrics.prom"
  rg 'redis_proxy_ask_redirect_total\{result="success"\}' "${LOG_DIR}/metrics.prom" >/dev/null
fi

echo "ASKING e2e passed for ${DATAPLANE}"
