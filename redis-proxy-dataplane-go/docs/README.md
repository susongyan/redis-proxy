# Redis Proxy Dataplane Go

Transparent RESP2 proxy MVP for latency and resource comparison.

## Run

```bash
go run ./cmd/proxy -config configs/proxy.yaml
```

Admin endpoints:

- `GET /healthz`
- `GET /readyz`
- `GET /metrics`
- `GET /debug/config`

## MVP Scope

- RESP2 request parsing and raw response forwarding
- TCP long connections
- Pipeline response ordering through sequential backend forwarding
- Standalone and simple cluster slot routing
- MOVED / ASK counters
- Prometheus metrics
- Graceful shutdown
