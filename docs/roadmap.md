# 实现路线与阶段状态

第一阶段：工程基线收敛

状态：进行中。

已完成：

1. Go / Java 数据面的基础单元测试和 smoke 脚本。
2. Go async / Java async 同组 benchmark 基线。
3. 直连 Redis baseline 脚本入口：`scripts/bench-direct-redis.sh`。
4. benchmark 结果报告生成脚本：`scripts/generate-bench-report.py`。
5. benchmark 基础资源采集：CPU、RSS、线程数、admin metrics 快照、Java GC log 摘要。
6. benchmark profile 脚本：`smoke`、`baseline`、`long`。
7. Go runtime metrics：heap、goroutine、process collector，并进入 benchmark 报告。
8. p999 近似采集：long profile 会额外采集 Redis benchmark percentile distribution，并取第一个大于等于 99.9% 的桶。

待完成：

1. Java Netty direct memory 深度指标校准和验证。
2. 固化生产级 benchmark 报告模板和长稳结论口径。

第二阶段：生产级路由

状态：核心能力已完成。`routeEpoch` 原子切换、灰度路由和回滚不纳入本阶段收敛，保留到后续路由发布阶段。

已完成：

1. Go 数据面启动时通过 `CLUSTER SLOTS` 构建 slot cache。
2. Go 数据面按真实 slot cache 路由，不再只使用 `slot % len(nodes)` 简化映射。
3. Go 数据面遇到 `MOVED` 响应时更新单 slot cache，并按需初始化新 backend 连接。
4. Go 数据面支持周期性 `CLUSTER SLOTS` 拓扑刷新，可通过 `routing.clusterSlotsRefreshIntervalSeconds` 配置，`0` 表示关闭。
5. Go 数据面暴露 slot cache 覆盖度、routeEpoch、slot refresh 成功/失败次数和最近成功刷新时间指标。
6. Java 数据面已对齐 `CLUSTER SLOTS` slot cache、周期刷新配置、MOVED 单 slot 更新和路由状态指标。
7. Java 数据面同一 client 到同一 backend 使用稳定后端连接亲和，避免依赖型 pipeline 在多后端连接间执行乱序。
8. Go 数据面同一 client 到同一 backend 也使用稳定后端连接亲和，并补充单元测试锁定该语义。
9. Go 数据面 backend connection 断开后会后台持续重连，并按原连接槽位替换，保留 client/backend 亲和语义。
10. Java 数据面 backend connection 断开后会由独立 scheduler 后台持续重连，限制全局和单 node 重连并发，并按原连接槽位替换。
11. Go / Java 数据面均对齐 Redis backend 异常、`MOVED`、`ASK`、master degraded 和 slot cache 刷新的处理语义。
12. Go / Java 数据面均实现真实 `/readyz`：standalone 检查默认 backend 可用，cluster 检查 slot coverage 和当前 slot owner backend 可用性。
13. Go / Java 数据面均补齐 per-node backend active / desired / reconnecting / reconnect / unavailable / inflight 指标口径。
14. 已完成 Go / Java standalone 停启恢复 E2E：Redis 停止时请求快速失败，Redis 恢复后 proxy 不重启恢复服务。
15. 已完成 Go / Java `7100-7105` cluster-local smoke、master 故障、Redis Cluster failover 和 degraded refresh E2E。
16. 已补充单元测试覆盖 `ASK` 不污染长期 slot cache、`MOVED` refresh 限频触发、backend reconnect 指标不为负和连接槽位替换。
17. Go / Java 数据面已支持 `ASKING` 临时路由：收到 `ASK` 后向临时 owner 同连接发送 `ASKING` + 原请求，跳过 `+OK` 后把真实响应返回客户端，且不污染长期 slot cache。

第二阶段后半：路由发布基础

状态：长轮询式动态路由快照发布已完成；namespace / key pattern / hash tag 路由和显式回滚已具备基础能力，发布审计和灰度发布平台化仍待增强。

已完成：

1. Go / Java 数据面和 Java 控制面均支持统一 `routing.rules` 配置模型。
2. `routing.rules` 支持按 `namespace` / `keyPrefix` / `keyPattern` / `hashTag` 匹配，并通过 key 的稳定 hash 做百分比灰度切流。
3. Go / Java 数据面均支持多 backend cluster 的路由选择；命中灰度规则时路由到规则指定 cluster，否则回到 `defaultCluster`。
4. cluster 模式下，Go / Java 数据面会按参与路由的 cluster 维护 slot cache 和 readiness 判断。
5. 控制面校验 route rule 引用的 cluster、namespace、流量百分比和 key 匹配条件，避免生成不可执行配置。
6. 单元测试已覆盖 Go / Java namespace、key pattern、hash tag、灰度路由选择和控制面 route rule 合法性校验。
7. Java 控制面提供 `GET /api/v1/config/watch?epoch=<current>&timeoutSeconds=<n>` 长轮询接口；无更新返回 `204`，有更大 `routeEpoch` 立即返回新配置。
8. Go / Java 数据面均从控制面长轮询 route snapshot，只接受单调递增的 `routeEpoch`，并用原子快照替换保证单请求只读一个版本。
9. Go / Java 数据面新增 `/debug/route-snapshot`，可查看当前生效 epoch、defaultCluster、rules 和参与路由的 clusters。
10. Go / Java 数据面新增 route epoch、snapshot update/reject、last success timestamp 和 route decision 指标。
11. 已完成动态切换 E2E：发布 epoch=2 后，Go / Java 均无需等待固定 poll interval 即切换快照，`user:*` 路由到 `redis-b`，默认 key 保持 `redis-a`。
12. Java 控制面提供显式发布治理 API：`POST /api/v1/config/publish`、`POST /api/v1/config/rollback`、版本历史、版本详情、diff 和 route status 查询。
13. 控制面发布历史记录 versionId、routeEpoch、operator、reason、action、approvalStatus 和完整 config snapshot。
14. 回滚采用更大 `routeEpoch` 表达：复制历史版本内容并设置为 `currentEpoch + 1`，不会要求数据面接受旧 epoch。
15. 控制面 `/api/v1/routes/status` 提供当前发布期望态；灰度真实命中量仍以数据面 Prometheus route decision 指标为准。
16. 已固化 `scripts/e2e-dynamic-route.sh`、`scripts/e2e-asking.sh` 和 `scripts/e2e-cluster-failover.sh`。
17. Go / Java 数据面支持本地 `instance.proxyId`，并在 `/debug/route-snapshot` 返回 `proxyId`、`configHash`、`lastApplyResult`、`lastApplyTime` 和 `lastPollTime`。
18. 控制面 `/api/v1/routes/status` 返回 `expectedVersionId`、`expectedRouteEpoch` 和 `expectedConfigHash`。
19. 控制面 Collector 已拉取每台 proxy 的 `/debug/route-snapshot`，并通过 `GET /api/v1/routes/convergence` 提供多 proxy 配置收敛状态。
20. 已固化 `scripts/e2e-route-convergence.sh`，覆盖 Go / Java 数据面 route snapshot hash 和控制面 `CONVERGED` 状态验证。
21. Go / Java 数据面和控制面均支持 `routing.rules[].matchAll`，用于整集群百分比切流。
22. 控制面新增 `ClusterSwitchPlan`，支持 staged 和 full 两种整集群切换模式，计划和发布版本均持久化。
23. 控制面新增 `/api/v1/cluster-switch/plans` 系列 API，支持创建、查询、预检、启动、推进、跳转、回滚和取消。
24. 整集群切换每一步都发布更大 `routeEpoch`，并依赖 `/api/v1/routes/convergence` 判断多 proxy 收敛后再推进。
25. 已固化 `scripts/e2e-cluster-switch.sh`，覆盖 Go / Java staged/full 切换和 rollback 验证。

多 Proxy 配置收敛观测：

- 控制面发布成功只表示期望态已经更新，不表示所有数据面实例都已拿到并生效相同配置。
- 多台 proxy 的收敛判断使用 `routeEpoch + configHash + proxyId` 三元组：`routeEpoch` 判断版本新旧，`configHash` 判断配置内容一致性，`proxyId` 标识具体实例。
- 数据面 `/debug/route-snapshot` 返回 `proxyId`、`epoch`、`configHash`、`lastApplyResult`、`lastApplyTime` 和 `lastPollTime`，用于定位未收敛、应用失败或长轮询异常的实例。
- Prometheus 继续只暴露低基数指标，例如 `route_epoch`、`route_snapshot_update_total` 和 `route_snapshot_last_success_timestamp_seconds`；不建议把 `configHash` 作为高频 metrics label。
- 控制面保存当前发布期望态：`expectedVersionId`、`expectedRouteEpoch`、`expectedConfigHash`，并由 Collector 定时拉取每台 proxy 的 `/debug/route-snapshot`。
- `GET /api/v1/routes/convergence` 返回整体收敛状态和逐实例明细。状态包括 `CONVERGED`、`PARTIAL`、`STALE`、`DRIFT` 和 `UNREACHABLE`。
- 灰度继续扩大比例或执行自动化双活切换前，建议要求所有健康 proxy 达到 `CONVERGED`；若出现 `DRIFT`，应暂停继续发布并优先排查配置生成或数据面应用路径。

待完成：

1. 接入真实审批流，使 `approvalStatus` 从审计字段升级为发布阻断条件。
2. 接入 Prometheus 查询或报表服务，在控制面聚合展示灰度真实命中量。
3. 为动态路由删除的 backend node 增加 retired 标记、inflight drain 和超时关闭，避免长期残留旧连接池。
4. 为整集群切换增加外部数据迁移校验回调，但不把数据复制逻辑放进 proxy 数据面。

第三阶段：治理能力

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

治理观测接入结论：

- 低基数治理指标继续通过数据面 `/metrics` 或 Java `/actuator/prometheus` 暴露，由 Prometheus / Grafana 定时 pull；这部分包括 auth、governance reject/warn、namespace limit、key rule decision、大 response、hot/large key tracked/dropped 等聚合指标。
- 热 key、大 key、慢查询这类具体明细不进入 Prometheus 高基数 label；具体 key 只通过受限 TopN debug endpoint 暴露，避免指标系统被 key 维度打爆。
- 控制面展示具体明细时，应由控制面 Observability Collector 定时 pull 数据面 debug endpoint，并补充 `proxyId`、cluster、namespace、采集时间等维度后进入控制面缓存或持久化存储；当前控制面 API 已支持跨 proxy 聚合和内存态历史查询。
- 控制面 Collector 对外查询结果尽量贴近 OpenTelemetry Resource 语义：用 `service.namespace`、`service.name`、`service.instance.id`、`deployment.environment.name` 表达实例身份和环境，用自定义低基数字段 `redis.proxy.dataplane`、`redis.proxy.cluster` 表达 proxy 专属维度；后续接 OTLP Collector、自研 APM 或统一 CMDB 时优先沿用这组资源属性。
- 观测存储默认使用控制面内存近期缓存；需要外接时可切换 `observability.storage.type=prometheus|otlp|influx`。Prometheus 模式由控制面暴露聚合低基数 endpoint，OTLP / Influx 模式由 Collector best-effort 写出，失败不影响数据面请求链路。
- 自研 APM 如果支持 Prometheus scrape，优先直接 scrape；如果只支持 push，应由 sidecar / node agent / collector 异步拉取 proxy 后再批量 push，不让 proxy 数据面直接依赖 APM 或控制面。
- 当前明细入口：热 key 使用 `/debug/hot-keys?limit=N`，大 key 使用 `/debug/large-keys?limit=N`，慢查询使用 `/debug/slow-queries?limit=N`。

待完成：

1. pipeline、请求大小治理指标联动。
2. 控制面观测数据接入真实生产 TSDB 的长稳压测和容量评估。
3. 治理审计持久化、token 加密存储和密钥管理接入。

性能专项计划已独立到 [performance-optimization-plan.md](performance-optimization-plan.md)。

第四阶段：控制面平台化

1. 配置生成、查询、发布和版本管理。
2. 路由变更审计和回滚。
3. 双活切换策略建模。
4. 与监控告警、CMDB、发布系统集成。

第五阶段：技术路线决策

1. 用长时间压测和真实 workload 对比 Go / Java。
2. 明确 p99 / p999、资源成本、运维复杂度和团队维护成本。
3. 如仍无法满足极致性能目标，再进入 Rust PoC。
