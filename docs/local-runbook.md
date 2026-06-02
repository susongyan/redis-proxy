# 本地运行与验证手册

启动 Redis standalone：

```bash
./scripts/redis-standalone-up.sh
```

启动 Go 数据面：

```bash
./scripts/run-go-dataplane.sh standalone
```

启动 Go 数据面连接本地 Redis Cluster：

```bash
./scripts/run-go-dataplane.sh cluster
```

如果本机 `7000-7005` 端口被占用，可以使用备用端口配置：

```bash
REDIS_CLUSTER_PORTS="7100 7101 7102 7103 7104 7105" ./scripts/redis-cluster-up.sh
./scripts/run-go-dataplane.sh cluster-local
REDIS_CLUSTER_PORTS="7100 7101 7102 7103 7104 7105" ./scripts/redis-cluster-down.sh
```

启动 Java 数据面：

```bash
./scripts/run-java-dataplane.sh standalone g1
./scripts/run-java-dataplane.sh standalone zgc
```

执行 smoke：

```bash
./scripts/smoke.sh
```

执行治理 E2E：

```bash
./scripts/e2e-governance.sh go
./scripts/e2e-governance.sh java
```

执行动态观测配置 E2E：

```bash
./scripts/e2e-observability-config.sh go
./scripts/e2e-observability-config.sh java
```

生成治理与观测巡检报告：

```bash
./scripts/generate-governance-observability-report.py \
  --admin-url http://127.0.0.1:8080 \
  --output-dir reports/governance-observability-local
```

报告会输出 `report.md`、`summary.json`、`metrics.prom`、`route-snapshot.json`、`hot-keys.json` 和 `large-keys.json`。这是单 proxy 进程的本地快照，用于巡检和压测上下文记录，不代表跨实例全局聚合结论。

执行治理与观测报告 E2E：

```bash
./scripts/e2e-governance-observability-report.sh go
./scripts/e2e-governance-observability-report.sh java
```

执行控制面治理观测 Collector E2E：

```bash
./scripts/e2e-observability-collector.sh go
./scripts/e2e-observability-collector.sh java
```

执行整集群切换 E2E：

```bash
./scripts/e2e-cluster-switch.sh go staged
./scripts/e2e-cluster-switch.sh java full
```

执行 benchmark：

```bash
REQUESTS=20000 CLIENTS_LIST="50 200" PIPELINE_LIST="1 10 100" TESTS="set,get" ./scripts/bench.sh
```

使用固定 benchmark profile：

```bash
./scripts/bench-profile.sh smoke proxy
./scripts/bench-profile.sh baseline proxy
./scripts/bench-profile.sh long proxy
./scripts/bench-profile.sh baseline redis-direct
```

profile 语义：

- `smoke`：1000 请求，10 连接，pipeline 1，用于快速验证脚本和资源采集。
- `baseline`：20000 请求，50/200 连接，pipeline 1/10/100，用于本地工程基线。
- `long`：1000000 请求，50/200 连接，pipeline 1/10/100，用于长稳 p99/p999 观察。

执行带资源采集的 benchmark：

```bash
BENCH_TARGET_PID=<proxy-pid> \
BENCH_TARGET_LABEL=go-async \
BENCH_ADMIN_URL=http://127.0.0.1:8080 \
REQUESTS=20000 CLIENTS_LIST="50 200" PIPELINE_LIST="1 10 100" TESTS="set,get" \
./scripts/bench.sh
```

Java 数据面可以额外带上 GC log：

```bash
BENCH_TARGET_PID=<java-pid> \
BENCH_TARGET_LABEL=java-g1-async \
BENCH_ADMIN_URL=http://127.0.0.1:8080 \
BENCH_JAVA_GC_LOG=redis-proxy-dataplane-java/target/gc-g1.log \
./scripts/bench.sh
```

执行直连 Redis baseline：

```bash
REQUESTS=20000 CLIENTS_LIST="50 200" PIPELINE_LIST="1 10 100" TESTS="set,get" ./scripts/bench-direct-redis.sh
```

从多个 benchmark 结果目录生成 Markdown 对比报告：

```bash
./scripts/generate-bench-report.py \
  --title "Redis Proxy Async Backend Comparison" \
  --output bench-results/comparison-$(date +%Y%m%d-%H%M%S).md \
  bench-results/redis-direct-standalone-xxx \
  bench-results/go-async-standalone-xxx \
  bench-results/java-g1-async-standalone-xxx
```


## 验证口径

数据面正确性：

- `PING` / `GET` / `SET` / `DEL`
- pipeline 响应顺序
- 大 value 转发
- 连接关闭与超时
- Redis Cluster slot 计算
- MOVED / ASK 指标
- healthz / readiness / metrics
- graceful shutdown

性能对比：

- 相同 Redis 后端。
- 相同 proxy 配置。
- 相同 client workload。
- 相同机器资源限制。
- 相同并发连接数和 pipeline depth。
- 区分 sync backend 和 async backend 实现模型。
- 同时采集 RPS、p50、p95、p99、p999、CPU、RSS、GC、heap/direct memory、backend inflight 和 error rate。

当前 benchmark 详情见 [tail-latency-comparison-workspace.md](tail-latency-comparison-workspace.md)。
