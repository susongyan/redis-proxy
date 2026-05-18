# Redis Proxy Dataplane Java

Netty-based transparent RESP2 proxy MVP for tail-latency comparison against the Go dataplane.

## Run

```bash
mvn spring-boot:run
```

Admin endpoints:

- `GET /healthz`
- `GET /readyz`
- `GET /debug/config`
- `GET /actuator/prometheus`

## MVP Scope

- Netty front-side TCP server
- RESP2 array request decoder
- Socket-based backend connection pool, no Lettuce/Jedis in the proxy hot path
- Standalone and simple cluster slot routing
- MOVED / ASK counters
- Micrometer metrics
