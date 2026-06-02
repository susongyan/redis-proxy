# 路由与集群调度

首版路由能力：

- standalone 单 Redis 转发。
- Redis Cluster slot 计算。
- 简化 slot 到节点映射。
- MOVED / ASK 指标暴露。

增强路线：

1. 接入真实 `CLUSTER SLOTS` 拓扑刷新。
2. 支持 routeEpoch，本地不可变路由快照。
3. 已支持按 namespace / key pattern / hash tag 路由到不同集群。
4. 已支持灰度切流、按比例切流和基于更大 routeEpoch 的快速回滚。
5. 已支持控制面整集群切换编排，方便运维迁移机器、上下云和机房搬迁。
6. 支持同城双活场景下的主读写集群切换。

同城双活建议把“决策”和“执行”分离：

- 控制面负责健康判断、切换计划、审批或自动化策略。
- 数据面只消费已发布的路由快照，按 routeEpoch 原子切换。
- 切换期间优先保证请求路径简单、确定、可观测。


## 生产级路由状态
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

## 路由发布基础

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

## 整集群切换

整集群切换用于 Redis 机器迁移、上下云、机房搬迁或默认集群更换。控制面只负责编排流量，不负责数据复制、双写或数据一致性校验。

支持两种模式：

- `FULL`：一次发布把 `routing.defaultCluster` 改为目标集群。
- `STAGED`：按默认 `0 -> 10 -> 25 -> 50 -> 100` 阶段发布临时 `matchAll` 规则；到 100% 时改写 `defaultCluster` 并移除临时规则。

核心 API：

- `POST /api/v1/cluster-switch/plans`
- `GET /api/v1/cluster-switch/plans`
- `GET /api/v1/cluster-switch/plans/{planId}`
- `POST /api/v1/cluster-switch/plans/{planId}/precheck`
- `POST /api/v1/cluster-switch/plans/{planId}/start`
- `POST /api/v1/cluster-switch/plans/{planId}/advance`
- `POST /api/v1/cluster-switch/plans/{planId}/jump`
- `POST /api/v1/cluster-switch/plans/{planId}/rollback`
- `POST /api/v1/cluster-switch/plans/{planId}/cancel`

推进保护：

- `sourceCluster` 必须等于当前 `defaultCluster`。
- 同一 source cluster 同一时间只允许一个未结束计划。
- 启动、推进、跳转和回滚前要求多 proxy 收敛状态为 `CONVERGED`。
- rollback 复制 baseline version 内容并发布更大 `routeEpoch`，不会要求数据面接受旧 epoch。

## 多 Proxy 配置收敛观测


- 控制面发布成功只表示期望态已经更新，不表示所有数据面实例都已拿到并生效相同配置。
- 多台 proxy 的收敛判断使用 `routeEpoch + configHash + proxyId` 三元组：`routeEpoch` 判断版本新旧，`configHash` 判断配置内容一致性，`proxyId` 标识具体实例。
- 数据面 `/debug/route-snapshot` 返回 `proxyId`、`epoch`、`configHash`、`lastApplyResult`、`lastApplyTime` 和 `lastPollTime`，用于定位未收敛、应用失败或长轮询异常的实例。
- Prometheus 继续只暴露低基数指标，例如 `route_epoch`、`route_snapshot_update_total` 和 `route_snapshot_last_success_timestamp_seconds`；不建议把 `configHash` 作为高频 metrics label。
- 控制面保存当前发布期望态：`expectedVersionId`、`expectedRouteEpoch`、`expectedConfigHash`，并由 Collector 定时拉取每台 proxy 的 `/debug/route-snapshot`。
- `GET /api/v1/routes/convergence` 返回整体收敛状态和逐实例明细。状态包括 `CONVERGED`、`PARTIAL`、`STALE`、`DRIFT` 和 `UNREACHABLE`。
- 灰度继续扩大比例或执行自动化双活切换前，建议要求所有健康 proxy 达到 `CONVERGED`；若出现 `DRIFT`，应暂停继续发布并优先排查配置生成或数据面应用路径。

## 待完成


1. 接入真实审批流，使 `approvalStatus` 从审计字段升级为发布阻断条件。
2. 接入 Prometheus 查询或报表服务，在控制面聚合展示灰度真实命中量。
3. 为动态路由删除的 backend node 增加 retired 标记、inflight drain 和超时关闭，避免长期残留旧连接池。
4. 为整集群切换增加外部数据迁移校验回调，但不把数据复制逻辑放进 proxy 数据面。
