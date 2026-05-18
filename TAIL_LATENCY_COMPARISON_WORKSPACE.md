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
- `long`：1000000 请求，50/200 连接，pipeline 1/10/100，用于长稳 p99 观察。
- `redis-direct` target 会直接压测 `63790` 端口的 standalone Redis。

常用 benchmark 参数：

```bash
REQUESTS=200000 CLIENTS_LIST="50 200" PIPELINE_LIST="1 10 100" ./scripts/bench.sh
```

结果会写入 `bench-results/`。

执行带资源采集的 benchmark：

```bash
BENCH_TARGET_PID=<proxy-pid> \
BENCH_TARGET_LABEL=go-async \
BENCH_ADMIN_URL=http://127.0.0.1:8080 \
REQUESTS=200000 CLIENTS_LIST="50 200" PIPELINE_LIST="1 10 100" \
./scripts/bench.sh
```

Java 数据面可以额外采集 GC log 摘要：

```bash
BENCH_TARGET_PID=<java-pid> \
BENCH_TARGET_LABEL=java-g1-async \
BENCH_ADMIN_URL=http://127.0.0.1:8080 \
BENCH_JAVA_GC_LOG=redis-proxy-dataplane-java/target/gc-g1.log \
REQUESTS=200000 CLIENTS_LIST="50 200" PIPELINE_LIST="1 10 100" \
./scripts/bench.sh
```

资源采集产物：

- `resource-c{clients}-p{pipeline}.csv`：每个 benchmark case 的 CPU/RSS/线程采样。
- `resource-summary.csv`：每个 case 的资源汇总。
- `metrics-before-*.prom` / `metrics-after-*.prom`：case 前后的 admin metrics 快照。
- `gc-*.log`：可选 Java GC log 复制件。

报告生成器会从 metrics 快照中提取：

- Go：`go_memstats_heap_alloc_bytes`、`go_goroutines`。
- Java：`jvm_memory_used_bytes{area="heap"}`、`jvm_buffer_memory_used_bytes{id="direct"}`。
- 缺失指标显示为 `n/a`，兼容历史结果。

执行直连 Redis baseline：

```bash
REQUESTS=200000 CLIENTS_LIST="50 200" PIPELINE_LIST="1 10 100" ./scripts/bench-direct-redis.sh
```

生成 benchmark 对比报告：

```bash
./scripts/generate-bench-report.py \
  --title "Redis Proxy Async Backend Comparison" \
  --output bench-results/comparison-$(date +%Y%m%d-%H%M%S).md \
  bench-results/redis-direct-standalone-xxx \
  bench-results/go-async-standalone-xxx \
  bench-results/java-g1-async-standalone-xxx \
  bench-results/java-zgc-async-standalone-xxx
```

`bench.sh` 会在 `metadata.txt` 中写入 `run_name`、`run_group`、`backend_model`、`dataplane`、`bench_profile`、`value_size` 等字段。旧的历史结果没有这些字段，报告生成器会自动按目录名回退。

当前 Redis 官方 `redis-benchmark --csv` 直接输出 p50 / p95 / p99，不直接输出 p999。长稳 profile 先用于观察长时间 p99、资源曲线和错误率；p999 需要后续引入更细粒度延迟采集工具或解析非 CSV 延迟分布。

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

## Java 数据面深度优化路线

当前阶段结论是：Java async 数据面已经明显优于 Java old sync backend，但在同属 async backend 的分组内仍落后于 Go async。Java 后续优化应继续围绕 Netty 数据链路、backpressure、JVM 内存和观测闭环推进，目标不是证明 Java 一定可胜出，而是明确 JVM/Netty 路线的性能上限和工程成本。

### 优先级 P0：正确性与压测口径收敛

优化前必须先保证对比口径稳定：

- 保持 Go async 和 Java async 的能力边界一致。
- 保持相同 `connectionsPerNode`、`maxInflightPerConnection`、`maxPipelineDepth`。
- 固定 Redis 后端、压测机、请求数、连接数、pipeline depth 和 workload。
- Java 每次压测前做 JVM warmup，避免把 JIT 冷启动混入尾延迟结论。
- 增加直连 Redis baseline，拆出 Redis 自身延迟、网络延迟和 proxy 额外开销。

必须补充采集：

- JVM heap、direct memory、metaspace。
- GC pause、GC count、allocation rate。
- Netty event loop pending tasks。
- Netty write buffer watermark 命中次数。
- backend active connections、backend inflight、pending acquire。
- client pending responses。
- ByteBuf leak detection 结果。

### 优先级 P1：Backpressure 与排队控制

Java async 后 p99 偏高，首要怀疑点不是 GC，而是异步化后 inflight 增大导致排队变长。需要把“能写入”改成“在可控队列深度内写入”。

建议实现：

- client 级 pending response 上限。
- backend connection 级 inflight 上限。
- backend node 级总 inflight 上限。
- Netty `Channel.isWritable()` 和 write buffer watermark 联动。
- 超限时明确策略：快速失败、短暂排队、或对 client 暂停读。
- 对 pipeline depth 100 / 1000 分别记录拒绝率和尾延迟变化。

验证目标：

- p99 / p999 不随 pipeline depth 线性恶化。
- backend inflight 达到阈值后 error rate 可解释。
- event loop pending tasks 不持续累积。

### 优先级 P1：Backend 连接选择策略

当前 Java async backend 可以继续优化连接选择。简单轮询或局部 least-inflight 在高并发下可能造成连接热点和 event loop 排队。

建议实现：

- least-inflight per backend node。
- event-loop aware 的连接选择，避免跨 event loop 频繁调度。
- 对同一 client 或同一 key hash tag 保持 backend connection affinity，避免破坏 pipeline 中具有顺序依赖的命令语义。
- backend channel 不可写时从候选集合剔除。
- 记录每条 backend connection 的 inflight、write queue、latency histogram。

验证目标：

- 每条 backend connection 的 inflight 分布更均匀。
- 高 pipeline 下最差 p99 下降。
- 不引入 pipeline 响应乱序或 Redis 执行顺序问题。

### 优先级 P1：Netty 线程模型与 event loop 隔离

Java 当前链路同时包含 Spring Boot admin、Micrometer、前端 Netty、后端 Netty。需要避免管理面和数据面互相干扰。

建议实现：

- 前端 boss / worker event loop 与后端 event loop 分离。
- admin HTTP 与 proxy TCP 完全隔离线程池。
- 固定 event loop 线程数并纳入 benchmark 元数据。
- 尝试 native transport：
  - Linux：epoll。
  - macOS：kqueue。
- 避免在 event loop 中执行复杂 metrics 标签构造、字符串解析或日志输出。

验证目标：

- event loop pending tasks 稳定。
- CPU 利用率更均匀。
- p99 / p999 对 admin metrics scrape 不敏感。

### 优先级 P2：ByteBuf 与拷贝优化

当前 Java 数据面已经避免使用 Lettuce/Jedis，但仍需要继续压低 ByteBuf retain/release 成本和中间对象。

建议实现：

- 请求转发优先使用 `retainedDuplicate()`，避免 `byte[]` 中间复制。
- 响应从 backend decoder 到 client write 尽量保持 `ByteBuf` 透传。
- 对错误响应使用预构造常量 buffer 或轻量复用策略。
- 减少命令名解析时的字符串创建，热点命令可用 ASCII byte compare。
- 对 MOVED / ASK 检测继续使用 ByteBuf prefix compare，避免整帧转字符串。
- benchmark 时开启 Netty leak detection 的 sampled 模式；专项测试时开启 paranoid 模式。

验证目标：

- allocation rate 下降。
- young GC 次数下降。
- direct memory 稳定，无 ByteBuf leak。

### 优先级 P2：Flush 策略与批量写

当前每个响应 `writeAndFlush` 会带来更多 syscall 和 event loop 压力。pipeline 场景可以引入批量 flush。

建议实现：

- 在 client response sequencer 中使用 `write` 聚合多个连续响应。
- 在同一 event loop tick 末尾统一 `flush`。
- 对 pipeline depth 较大场景使用 configurable batch size。
- 结合 write buffer watermark 控制 batch，不让低延迟小请求被大 pipeline 长时间压住。

验证目标：

- pipeline 10 / 100 下 RPS 提升。
- p99 不因 batch 等待明显变差。
- syscall 和 event loop pending tasks 下降。

### 优先级 P2：JVM 与 GC 参数矩阵

当前短压测中 G1 async 优于 ZGC async，但这个结论不能直接外推。需要用更完整参数矩阵验证。

建议测试：

- Java 21 + G1GC：
  - 固定 `-Xms` / `-Xmx`。
  - 调整 `MaxGCPauseMillis`。
  - 观察 young GC 与 mixed GC 对 p99/p999 的影响。
- Java 21 + ZGC：
  - 固定 heap。
  - 观察低暂停是否能抵消吞吐损失。
  - 关注 allocation rate 高时的 CPU 成本。
- Direct memory：
  - 设置 `-XX:MaxDirectMemorySize`。
  - 配合 Netty allocator metrics 观察 direct arena 使用。

验证目标：

- 用同一 workload 输出 G1 / ZGC 的 p99、p999、CPU、heap、direct memory、GC pause。
- 判断 Java 尾延迟瓶颈是 GC、event loop、direct memory、还是排队。

### 优先级 P3：协议解析专项优化

如果 P1/P2 后 Java 仍明显落后，再进入 parser 级优化。

建议实现：

- JMH benchmark 覆盖 RESP request decoder 和 response frame decoder。
- 使用 byte-level parser，减少边界检查和对象创建。
- 针对常见命令 `GET`、`SET`、`DEL`、`MGET` 做轻量 fast path。
- 解析只提取路由所需 key 和 command，不构造完整对象树。

验证目标：

- parser allocation 接近零。
- 小 value、高 QPS 场景 CPU 降低。
- 不影响大 value 和 nested array 的正确性。

### Java 优化后的重新判定标准

Java 深度优化完成后，再和 Go async 重新对比。建议至少满足以下条件后再调整技术路线判断：

- 同一 workload 下 Java async 平均 RPS 接近或超过 Go async 的 `80%`。
- Java async 平均 p99 不超过 Go async 的 `150%`。
- p999 在长时间压测中没有明显尖刺。
- direct memory 无泄漏，GC pause 可解释。
- 实现复杂度、排障成本和团队维护成本可接受。

如果达不到这些条件，Java 数据面继续作为对照实现和技术储备，主路线保持 Go 数据面 + Java 控制面。

## 后续建议

1. 为 Java async backend 增加更严格的 backpressure：client pending response、backend inflight、write buffer watermark 都需要参与限流。
2. 优化 backend connection 选择策略，从简单轮询升级为 least-inflight / event-loop aware。
3. 在 `scripts/bench.sh` 中补充 CPU、RSS、heap、direct memory、GC pause、event loop pending tasks 采集。
4. 增加直连 Redis benchmark，量化 proxy 自身开销。
5. 延长压测时长，例如每个 case 5-10 分钟，并加入 JVM warmup。
6. 将 Redis Cluster 路由从简化 slot 映射升级为真实 `CLUSTER SLOTS` 拓扑刷新。
