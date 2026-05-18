# Redis Proxy 尾延迟对比实验工作区

当前工作区包含三个独立项目：

- `redis-proxy-dataplane-go`：Go 版透明 Redis Proxy 数据面。
- `redis-proxy-dataplane-java`：Java 21 + Netty 版透明 Redis Proxy 数据面。
- `redis-proxy-control-plane-java`：Java 21 + Spring Boot 控制面契约骨架。

## 当前 MVP 范围

Go 和 Java 两个数据面当前对齐同一组第一阶段能力：

- RESP2 请求解析。
- 原始 RESP 响应转发。
- TCP 长连接。
- pipeline 响应顺序保持。
- standalone 和简化版 Redis Cluster slot 路由。
- `MOVED` / `ASK` 指标。
- health/readiness/admin 接口。
- 与控制面模型兼容的本地静态配置。

暂不包含：

- namespace 鉴权。
- 完整限流。
- 热 key TopK。
- 离线大 key 分析。
- 控制面动态推送。
- 同城双活 / 主备切换编排。

## 已验证基线

当前脚手架已通过基础单元测试：

```bash
cd redis-proxy-dataplane-go
go test ./...

cd ../redis-proxy-dataplane-java
mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 test

cd ../redis-proxy-control-plane-java
mvn -Dmaven.repo.local=/tmp/redis-proxy-m2 test
```

Java 数据面是为了尾延迟对比而实现的实验版本，并不代表最终一定选择 Java 作为生产数据面。

## Benchmark 结果快照

最新对比报告：

- [bench-results/comparison-go-java-async-20260518-155344.md](bench-results/comparison-go-java-async-20260518-155344.md)

测试场景：

- Redis standalone 后端。
- 使用 Docker 容器内的 `redis-benchmark`。
- 每个 case 20k 请求。
- 命令：`SET`、`GET`。
- 并发连接数：`50`、`200`。
- pipeline 深度：`1`、`10`、`100`。

聚合结果：

| 分组 | 实现 | backend 实现方式 | client pipeline 方式 | 平均 RPS | 平均 p99 ms | 最好 RPS | 最差 p99 ms |
|---|---|---|---|---:|---:|---:|---:|
| sync backend | Go baseline | blocking backend socket，每条 backend 连接一次只处理一个请求 | 单 client 串行 backend 转发 | 4163.93 | 112.63 | 5885.81 | 209.02 |
| sync backend | Java G1 old | blocking backend socket，每条 backend 连接一次只处理一个请求 | 单 client 串行 backend 转发 | 1949.24 | 260.43 | 2594.03 | 578.05 |
| sync backend | Java ZGC old | blocking backend socket，每条 backend 连接一次只处理一个请求 | 单 client 串行 backend 转发 | 1842.41 | 255.10 | 2281.02 | 458.75 |
| async backend | Go async | goroutine backend 连接池，每条 backend 连接维护 FIFO inflight 队列 | 异步 backend 转发 + client response sequencer 保序 | 21336.61 | 168.94 | 54200.54 | 344.06 |
| async backend | Java G1 async | Netty backend 连接池，每条 backend 连接维护 FIFO inflight 队列 | 异步 backend 转发 + client response sequencer 保序 | 9343.40 | 311.86 | 22547.91 | 763.39 |
| async backend | Java ZGC async | Netty backend 连接池，每条 backend 连接维护 FIFO inflight 队列 | 异步 backend 转发 + client response sequencer 保序 | 8649.93 | 371.02 | 18433.18 | 1156.10 |

初步结论：

- Java 数据面已经从 blocking backend socket + 单 client 串行后端转发，优化为 Netty async backend 连接池 + client response sequencer。
- Go 数据面也已经从 sync backend 模型优化为 goroutine async backend 连接池 + FIFO inflight queue + client response sequencer。为了保持 Redis pipeline 语义，同一 client 对同一 backend 地址固定绑定到同一 backend 连接。
- `Go baseline` 和 `Java old` 属于同一组 sync backend 对比；`Go async` 和 `Java async` 属于同一组 async backend 对比。跨组只能说明实现模型变化后的工程效果，不能直接作为语言优劣结论。
- Go async 相比 Go baseline，平均 RPS 提升 `412.4%`，平均 p99 上升 `50.0%`。这符合异步化后的典型特征：吞吐释放，但真实 inflight 增加后尾延迟也会上升。
- 在 async backend 同组对比里，Go async 当前平均 RPS `21336.61`，高于 Java G1 async `9343.40` 和 Java ZGC async `8649.93`；Go async 平均 p99 `168.94ms`，低于 Java G1 async `311.86ms` 和 Java ZGC async `371.02ms`。
- 本轮短压测里 G1 async 的平均 RPS 和平均 p99 都优于 ZGC async；这不代表 ZGC 不适合，仍需要更长时间、充分 warmup、GC/direct memory 指标采集后判断。
- 当前结论是工程基线：Go async 在本地短压测中同时取得更高吞吐和更低 p99；Java async 仍需要继续优化 backpressure、backend 连接选择、event loop 隔离和 direct memory/GC 观测。最终技术路线还需要加入直连 Redis baseline、CPU/RSS/GC 采集和更长时间压测。

## 运行方式

启动本地 Redis standalone 后端：

```bash
./scripts/redis-standalone-up.sh
```

启动 Go 数据面，连接 standalone Redis：

```bash
./scripts/run-go-dataplane.sh standalone
```

启动 Java 数据面，连接 standalone Redis，使用 G1GC：

```bash
./scripts/run-java-dataplane.sh standalone g1
```

启动 Java 数据面，使用 ZGC：

```bash
./scripts/run-java-dataplane.sh standalone zgc
```

启动 Java 控制面：

```bash
cd redis-proxy-control-plane-java && mvn spring-boot:run
```

## 本地 Redis 环境

需要安装 Docker，并确保 Docker daemon 正在运行。

宿主机不需要安装 `redis-cli`、`redis-server` 或 `redis-benchmark`，脚本会使用官方 Redis 镜像里的工具。

Standalone Redis 使用宿主机端口 `63790`，避免和 proxy 默认端口 `6379` 冲突：

```bash
./scripts/redis-standalone-up.sh
./scripts/redis-standalone-down.sh
```

Redis Cluster 使用宿主机端口 `7000-7005`：

```bash
./scripts/redis-cluster-up.sh
./scripts/redis-cluster-down.sh
```

当前 Redis Cluster 路由实现是简化版本，适合早期 smoke 验证和指标暴露，还不是生产级 `CLUSTER SLOTS` 拓扑实现。

## Smoke 与 Benchmark

对当前监听在 `127.0.0.1:6379` 的数据面执行 smoke 测试：

```bash
./scripts/smoke.sh
```

`smoke.sh` 会验证：

- `PING`
- `SET`
- `GET`
- `DEL`
- pipeline 响应顺序
- 大 value 基础转发
- `/healthz`
- metrics endpoint

脚本默认使用 Docker 容器内的 `redis-cli`，因此宿主机不需要安装 Redis 工具。

可以通过环境变量覆盖目标：

```bash
PROXY_HOST=127.0.0.1 PROXY_PORT=6379 ADMIN_URL=http://127.0.0.1:8080 ./scripts/smoke.sh
```

执行基线 benchmark：

```bash
./scripts/bench.sh
```

常用 benchmark 参数：

```bash
REQUESTS=200000 CLIENTS_LIST="50 200" PIPELINE_LIST="1 10 100" ./scripts/bench.sh
```

结果会写入 `bench-results/`。

## 对比口径

对比 Go / Java G1 / Java ZGC 时，需要尽量保持以下条件一致：

- 相同 Redis 后端。
- 相同 proxy 配置。
- 相同 client workload。
- 相同机器资源限制。
- 相同并发连接数。
- 相同 pipeline 深度。
- 相同预热和测试时长。

建议统一采集：

- QPS / RPS。
- p50 / p90 / p99 / p999 延迟。
- CPU。
- RSS。
- heap / direct memory。
- GC pause。
- active connections。
- backend inflight。
- error rate。

Java 数据面至少需要分别测试 G1GC 和 ZGC，再做判断。

## 后续建议

1. 为 Java async backend 增加更严格的 backpressure：client pending response、backend inflight、write buffer watermark 都需要参与限流。
2. 优化 backend connection 选择策略，从简单轮询升级为 least-inflight / event-loop aware。
3. 在 `scripts/bench.sh` 中补充 CPU、RSS、heap、direct memory、GC pause、event loop pending tasks 采集。
4. 增加直连 Redis benchmark，量化 proxy 自身开销。
5. 延长压测时长，例如每个 case 5-10 分钟，并加入 JVM warmup。
6. 将 Redis Cluster 路由从简化 slot 映射升级为真实 `CLUSTER SLOTS` 拓扑刷新。
