# 治理与观测能力

第三阶段第一批已落地治理 MVP：Go / Java 数据面都支持 `AUTH <namespace> <token>` 绑定连接身份，治理开启后未认证请求默认返回 `NOAUTH`，并在本地快照中执行命令治理、只读 namespace 和可选 key prefix 约束。

已实现边界：

- 接入治理：namespace、应用身份、Redis 资源绑定关系。
- 访问控制：命令黑白名单、只读 namespace、危险命令拦截。
- 命令策略：默认拒绝 `FLUSHALL`、`FLUSHDB`、`CONFIG`、`SHUTDOWN`、`DEBUG`、`MODULE`；`KEYS`、`EVAL`、`SCRIPT` 第一版只打点不拒绝。
- key 约束：namespace 可配置 `allowedKeyPrefixes`，为空表示不限制；非空时只允许已支持命令的 key 位置全部匹配前缀。
- 动态生效：治理规则纳入 route snapshot，控制面发布更大 `routeEpoch` 后随长轮询原子切换。
- 观测：数据面暴露 auth、governance reject 和 governance warn 指标；`/debug/route-snapshot` 只返回治理摘要，不暴露 token。

后续治理能力分层：

- 限流降级：连接数、QPS、pipeline depth、inflight、请求大小、响应大小。
- 热 key 分析：在数据面做轻量采样和 TopK 近实时统计，重分析放异步链路。
- 大 key / 大 response 分析：同步路径只做大小计数、阈值打点和采样，离线分析由旁路任务完成。
- 审计：高风险命令、跨 namespace 访问、切换操作和异常流量记录。

热 key 和大 key 可以在 Proxy 层做，但需要控制边界：

- 可以做：采样、计数、TopK、阈值告警、按 namespace 聚合。
- 不建议在同步路径做：全量精确统计、复杂聚合、阻塞式大 key 扫描。
- 大 key 更准确的判断应结合 Redis 侧 `MEMORY USAGE`、离线扫描和 response size 观测。


## 第三阶段治理状态

状态：第一批治理 MVP 已完成，namespace / key 级本地治理限流已进入实现收敛；热 key 与大 key 的本地分析 MVP 已落地，后续进入更细粒度指标联动和报告化。

已完成：

1. Go / Java 数据面支持 `AUTH <namespace> <token>`，按连接绑定 namespace。
2. 治理开启后默认未认证拒绝，支持 namespace 删除后的 `NOAUTH namespace disabled` 语义。
3. 支持全局和 namespace 级危险命令拦截、warn-only 命令打点、只读 namespace。
4. 支持 namespace 可选 `allowedKeyPrefixes`，对未知 key 位置命令 fail closed。
5. 控制面 `ProxyConfig`、publish、rollback、diff 和 status 支持 governance 配置。
6. 治理规则随 route snapshot 长轮询动态生效，Go / Java 均有 `e2e-governance.sh` 覆盖。
7. 支持 namespace 维度连接数、QPS、inflight 限制。
8. 支持 key 精确禁用、keyPrefix/hashTag 规则禁用，以及基于滑动窗口的 key rule QPS 限流。
9. 补齐治理与限流可观测性：配置限额、当前连接/inflight、限流拒绝、key rule 决策和滑动窗口用量。
10. 支持本地进程级热 key TopK 轻量采样，基于 60s 滑动窗口，提供 debug 查询和低基数 TopK 指标。
11. 支持大 response 软阈值观测，记录响应大小分布和超过阈值的命中次数。
12. `largeResponseBytes`、热 key 窗口、容量和 metrics TopN 已配置化，并随控制面 route snapshot 动态生效。
13. 已固化 `scripts/e2e-observability-config.sh`，覆盖 Go / Java 动态观测配置切换。
14. 支持本地进程级大 key 分析，按 `namespace + command + key` 归因请求/响应大小，提供 `/debug/large-keys` 查询和低基数 metrics。
15. `analysis.largeKey.*` 已纳入控制面 publish、rollback、copy、diff、validation 模型，并随 route snapshot 动态生效。
16. 动态观测配置 E2E 已覆盖大 key 阈值发布、debug 查询和 metrics 不泄露具体 key。
17. 支持治理与观测报告化，脚本从 admin/debug/metrics 拉取本地快照，生成 Markdown + JSON + 原始 Prometheus/Debug 文件。
18. 已固化 `scripts/e2e-governance-observability-report.sh`，覆盖 Go / Java 报告生成、token 不泄露和大 key metrics 低基数约束。
19. 支持控制面 Observability Collector MVP：控制面注册 proxy admin target，按 OpenTelemetry Resource 语义记录 `service.namespace`、`service.name`、`service.instance.id`、`deployment.environment.name`，定时 pull `/metrics`、`/debug/hot-keys`、`/debug/large-keys`，并提供 summary、hot key、large key 和慢查询查询 API。
20. 已固化 `scripts/e2e-observability-collector.sh`，覆盖 Go / Java target 注册、采集、查询、token 不泄露和慢查询明细语义。
21. 支持本地进程级慢查询 TopN，按 `namespace + command + key` 归因端到端延迟和 backend 延迟，提供 `/debug/slow-queries` 查询和低基数 metrics。
22. 控制面 Observability Collector 支持内存近期缓存、历史查询、跨 proxy 聚合和聚合 Prometheus endpoint，并预留 `memory` / `prometheus` / `otlp` / `influx` 存储切换配置。

## 治理观测接入结论

- 低基数治理指标继续通过数据面 `/metrics` 或 Java `/actuator/prometheus` 暴露，由 Prometheus / Grafana 定时 pull；这部分包括 auth、governance reject/warn、namespace limit、key rule decision、大 response、hot/large key tracked/dropped 等聚合指标。
- 热 key、大 key、慢查询这类具体明细不进入 Prometheus 高基数 label；具体 key 只通过受限 TopN debug endpoint 暴露，避免指标系统被 key 维度打爆。
- 控制面展示具体明细时，应由控制面 Observability Collector 定时 pull 数据面 debug endpoint，并补充 `proxyId`、cluster、namespace、采集时间等维度后进入控制面缓存或持久化存储；当前控制面 API 已支持跨 proxy 聚合和内存态历史查询。
- 控制面 Collector 对外查询结果尽量贴近 OpenTelemetry Resource 语义：用 `service.namespace`、`service.name`、`service.instance.id`、`deployment.environment.name` 表达实例身份和环境，用自定义低基数字段 `redis.proxy.dataplane`、`redis.proxy.cluster` 表达 proxy 专属维度；后续接 OTLP Collector、自研 APM 或统一 CMDB 时优先沿用这组资源属性。
- 观测存储默认使用控制面内存近期缓存；需要外接时可切换 `observability.storage.type=prometheus|otlp|influx`。Prometheus 模式由控制面暴露聚合低基数 endpoint，OTLP / Influx 模式由 Collector best-effort 写出，失败不影响数据面请求链路。
- 自研 APM 如果支持 Prometheus scrape，优先直接 scrape；如果只支持 push，应由 sidecar / node agent / collector 异步拉取 proxy 后再批量 push，不让 proxy 数据面直接依赖 APM 或控制面。
- 当前明细入口：热 key 使用 `/debug/hot-keys?limit=N`，大 key 使用 `/debug/large-keys?limit=N`，慢查询使用 `/debug/slow-queries?limit=N`。

## 指标与分析明细

### 核心治理指标

| 语义 | Go 指标 | Java 指标 |
| --- | --- | --- |
| AUTH 结果 | `redis_proxy_auth_total{namespace,result}` | `redis.proxy.auth{namespace,result}` |
| 治理拒绝 | `redis_proxy_governance_reject_total{namespace,command,reason}` | `redis.proxy.governance.reject{namespace,command,reason}` |
| 治理告警 | `redis_proxy_governance_warn_total{namespace,command,reason}` | `redis.proxy.governance.warn{namespace,command,reason}` |
| namespace 连接数 | `redis_proxy_namespace_connections{namespace}` | `redis.proxy.namespace.connections{namespace}` |
| namespace inflight | `redis_proxy_namespace_inflight{namespace}` | `redis.proxy.namespace.inflight{namespace}` |
| namespace 限额配置 | `redis_proxy_namespace_limit_config{namespace,limit}` | `redis.proxy.namespace.limit.config{namespace,limit}` |
| namespace 限流拒绝 | `redis_proxy_namespace_limit_reject_total{namespace,limit}` | `redis.proxy.namespace.limit.reject{namespace,limit}` |
| key 治理拒绝 | `redis_proxy_key_governance_reject_total{namespace,rule,command,reason}` | `redis.proxy.key.governance.reject{namespace,rule,command,reason}` |
| key rule 决策 | `redis_proxy_key_governance_decisions_total{namespace,rule,command,result,reason}` | `redis.proxy.key.governance.decisions{namespace,rule,command,result,reason}` |
| key 限额配置 | `redis_proxy_key_limit_config{namespace,rule}` | `redis.proxy.key.limit.config{namespace,rule}` |
| key 滑窗用量 | `redis_proxy_key_limit_window_usage{namespace,rule}` | `redis.proxy.key.limit.window.usage{namespace,rule}` |

### 热 key 观测

- Go / Java 数据面都会在治理通过、进入路由前，对已支持 key 解析的命令做本地滑动窗口计数。
- TopK 默认统计最近 60s 窗口，bucket 粒度 1s；可通过 `analysis.hotKey.windowSeconds` / `bucketMillis` 调整，过期 key 会在观测或 debug 查询时被清理。
- 进程内默认最多跟踪 10000 个 `namespace + command + key` 组合，可通过 `analysis.hotKey.maxTrackedKeys` 调整；超过上限的新 key 会被跳过并计入 dropped 指标。
- `/debug/hot-keys?limit=20` 返回当前进程内 TopK 明细。
- metrics 默认只暴露 Top 20，可通过 `analysis.hotKey.metricsTopN` 调整，避免把全量 key 写入高基数 label。

| 语义 | Go 指标 | Java 指标 |
| --- | --- | --- |
| 已观测 key 数 | `redis_proxy_hot_key_observed_total{namespace,command}` | `redis.proxy.hot.key.observed{namespace,command}` |
| 容量满后丢弃数 | `redis_proxy_hot_key_dropped_total{namespace,command}` | `redis.proxy.hot.key.dropped{namespace,command}` |
| 当前跟踪 key 数 | `redis_proxy_hot_key_tracked_keys` | `redis.proxy.hot.key.tracked.keys` |
| TopK 计数 | `redis_proxy_hot_key_topk_count{namespace,command,key,rank}` | `redis.proxy.hot.key.topk.count{namespace,command,key,rank}` |

### 大 key 观测

- Go / Java 数据面都会在治理通过后复用治理 key parser 做本地大 key 归因，维度为 `namespace + command + key`。
- `requestBytesThreshold` 和 `responseBytesThreshold` 默认 1MB；请求或最终响应超过阈值时才进入大 key 窗口统计，不拦截请求。
- 窗口默认 300s，bucket 粒度 1s；`maxTrackedKeys` 控制进程内最多跟踪的组合数量，容量满后的新 key 计入 dropped 指标。
- 多 key 命令暂按完整请求/响应大小归因到每个可解析 key；无法识别 key 位置的命令不影响转发，只计入 unsupported。
- `/debug/large-keys?limit=100` 返回当前进程 TopN 明细，按 `max(requestBytes,responseBytes)` 降序。
- Prometheus / Micrometer 只暴露低基数指标，不把真实 key 写入 metrics label，避免大规模 key 造成高基数风险。

| 语义 | Go 指标 | Java 指标 |
| --- | --- | --- |
| 大 key 观测次数 | `redis_proxy_large_key_observed_total{namespace,command,direction}` | `redis.proxy.large.key.observed{namespace,command,direction}` |
| 容量满后丢弃数 | `redis_proxy_large_key_dropped_total{namespace,command}` | `redis.proxy.large.key.dropped{namespace,command}` |
| 无法归因命令数 | `redis_proxy_large_key_unsupported_total{command,direction}` | `redis.proxy.large.key.unsupported{command,direction}` |
| 当前跟踪 key 数 | `redis_proxy_large_key_tracked_keys` | `redis.proxy.large.key.tracked.keys` |
| 请求阈值 | `redis_proxy_large_key_request_threshold_bytes` | `redis.proxy.large.key.request.threshold.bytes` |
| 响应阈值 | `redis_proxy_large_key_response_threshold_bytes` | `redis.proxy.large.key.response.threshold.bytes` |

### 慢查询观测

- Go / Java 数据面都会在最终响应按 pipeline 顺序写回前记录慢查询，维度为 `namespace + command + key`。
- 慢查询同时记录两个口径：端到端延迟是 proxy 收到请求到写回前，backend 延迟是请求进入 backend pool 到最终 backend 响应或错误完成，`ASKING` retry 计入同一次 backend 耗时。
- 默认阈值为端到端 100ms、backend 50ms；窗口默认 300s，bucket 粒度 1s，容量默认 10000。
- `/debug/slow-queries?limit=100` 返回当前进程 TopN 明细，包含 `maxEndToEndMillis` 和 `maxBackendMillis`。
- Prometheus / Micrometer 只暴露低基数指标，不把真实 key 写入 metrics label。

| 语义 | Go 指标 | Java 指标 |
| --- | --- | --- |
| 慢查询观测次数 | `redis_proxy_slow_query_observed_total{namespace,command,trigger}` | `redis.proxy.slow.query.observed{namespace,command,trigger}` |
| 容量满后丢弃数 | `redis_proxy_slow_query_dropped_total{namespace,command}` | `redis.proxy.slow.query.dropped{namespace,command}` |
| 无法归因命令数 | `redis_proxy_slow_query_unsupported_total{command}` | `redis.proxy.slow.query.unsupported{command}` |
| 当前跟踪 key 数 | `redis_proxy_slow_query_tracked_keys` | `redis.proxy.slow.query.tracked.keys` |
| 端到端阈值 | `redis_proxy_slow_query_end_to_end_threshold_millis` | `redis.proxy.slow.query.end.to.end.threshold.millis` |
| backend 阈值 | `redis_proxy_slow_query_backend_threshold_millis` | `redis.proxy.slow.query.backend.threshold.millis` |

### 大 response 观测

- `maxResponseBytes` 是硬上限，超过后 backend frame 读取失败并返回 backend unavailable。
- `largeResponseBytes` 是软阈值，默认 1MB；超过只打指标，不拦截请求；控制面发布更大 `routeEpoch` 后可动态调整。
- 统计发生在最终响应写回客户端前，`ASKING` 的中间 `+OK` 不计入最终业务响应。

| 语义 | Go 指标 | Java 指标 |
| --- | --- | --- |
| 响应大小分布 | `redis_proxy_response_bytes{command}` | `redis.proxy.response.bytes{command}` |
| 大 response 命中 | `redis_proxy_large_response_total{command}` | `redis.proxy.large.response{command}` |
| 大 response 阈值 | `redis_proxy_large_response_threshold_bytes` | `redis.proxy.large.response.threshold.bytes` |

## 待完成

1. pipeline、请求大小治理指标联动。
2. 控制面观测数据接入真实生产 TSDB 的长稳压测和容量评估。
3. 治理审计持久化、token 加密存储和密钥管理接入。
