# Redis Proxy Control Plane Java

Spring Boot control-plane contract skeleton.

## Run

```bash
mvn spring-boot:run
```

Endpoints:

- `GET /healthz`
- `GET /api/v1/config`
- `PUT /api/v1/config`
- `GET /openapi.json`
- `GET /docs`

The first version keeps config in memory. It is intended to define the shared contract used by both dataplanes.
