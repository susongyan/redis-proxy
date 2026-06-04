# 统一配置契约

Go / Java 数据面和 Java 控制面共享同一语义配置。

## 配置示例

```yaml
instance:
  proxyId: "" # 为空时自动生成 group-ip-port
  group: "frontend"
  advertiseIp: "" # 为空时自动探测本机非 loopback IP
  advertisePort: 0 # 为空时使用 server.listen 的数据面端口

server:
  listen: "0.0.0.0:6379"

admin:
  listen: "0.0.0.0:8080"

mode: "cluster"

backends:
  clusters:
    - name: "redis-a"
      nodes:
        - "127.0.0.1:7000"
        - "127.0.0.1:7001"
        - "127.0.0.1:7002"
      auth:
        enabled: false
        username: ""
        password: ""
      pool:
        connectionsPerNode: 16
        maxInflightPerConnection: 4096
    - name: "redis-b"
      nodes:
        - "127.0.0.1:7100"
        - "127.0.0.1:7101"
        - "127.0.0.1:7102"
      auth:
        enabled: false
        username: ""
        password: ""
      pool:
        connectionsPerNode: 16
        maxInflightPerConnection: 4096

routing:
  defaultCluster: "redis-a"
  routeEpoch: 1
  clusterSlotsRefreshIntervalSeconds: 30
  backendAffinityStrategy: "client" # client | keySlot | hashTag
  rules:
    - name: "gray-user-cache"
      cluster: "redis-b"
      namespace: "app-a"
      keyPrefix: "user:"
      keyPattern: "user:*:profile"
      trafficPercent: 10
    - name: "cluster-switch-1001"
      cluster: "redis-b"
      matchAll: true
      trafficPercent: 25
    - name: "order-hash-tag"
      cluster: "redis-b"
      hashTag: "order"
      trafficPercent: 100

proxyGroups:
  - name: "frontend"
    enabledClusters: ["redis-a", "redis-b"]
    routing:
      defaultCluster: "redis-a"
      backendAffinityStrategy: "client"
      rules:
        - name: "frontend-app-a"
          namespace: "app-a"
          cluster: "redis-b"
          trafficPercent: 100
  - name: "payment"
    enabledClusters: ["redis-b"]
    routing:
      defaultCluster: "redis-b"
      backendAffinityStrategy: "keySlot"
      rules: []

limits:
  maxPipelineDepth: 1024
  pipelineFlushBatchSize: 16
  pipelineFlushMaxDelayMillis: 1
  maxRequestBytes: 10485760
  maxResponseBytes: 104857600
  largeResponseBytes: 1048576

analysis:
  hotKey:
    enabled: true
    windowSeconds: 60
    bucketMillis: 1000
    maxTrackedKeys: 10000
    metricsTopN: 20
  largeKey:
    enabled: true
    requestBytesThreshold: 1048576
    responseBytesThreshold: 1048576
    windowSeconds: 300
    bucketMillis: 1000
    maxTrackedKeys: 10000
    debugTopN: 100

controlPlane:
  enabled: false
  url: "http://127.0.0.1:8090/api/v1/config"
  pollIntervalSeconds: 5
  watchTimeoutSeconds: 30
  requestTimeoutMillis: 1000

registration:
  enabled: false
  controlPlaneUrl: "http://127.0.0.1:8090/api/v1"
  adminUrl: "http://127.0.0.1:8080"
  dataplane: "go" # go | java
  cluster: "redis-a"
  heartbeatIntervalSeconds: 15
  pollIntervalSeconds: 15
  serviceNamespace: "redis-proxy"
  serviceName: "redis-proxy-dataplane"
  serviceInstanceId: ""
  deploymentEnvironmentName: ""

governance:
  enabled: false
  requireAuth: true
  keyLimitWindowMillis: 1000
  keyLimitBucketMillis: 100
  commandPolicy:
    deniedCommands: ["FLUSHALL", "FLUSHDB", "CONFIG", "SHUTDOWN", "DEBUG", "MODULE"]
    warnOnlyCommands: ["KEYS", "EVAL", "SCRIPT"]
  namespaces:
    - name: "app-a"
      token: "token-a"
      readOnly: false
      allowedKeyPrefixes: ["app-a:"]
      deniedCommands: []
      warnOnlyCommands: []
      limits:
        maxConnections: 0
        maxQps: 0
        maxInflight: 0
      disabledKeys: ["app-a:blocked"]
      keyRules:
        - name: "hot-user"
          keyPrefix: "app-a:hot:"
          disabled: false
          maxQps: 1000
```

## 配置原则


- 启动强校验，非法配置 fail fast。
- 数据面运行时使用本地不可变快照。
- `routeEpoch` 表示当前路由快照版本；数据面按快照进行原子路由选择，不在单请求中混用多个版本。
- `routing.rules` 按顺序匹配，支持 `namespace` / `keyPrefix` / `keyPattern` / `hashTag` / `matchAll` 和稳定百分比灰度，未命中时回到 `defaultCluster`。
- `proxyGroups` 用于大规模部署下的分组快照裁剪；控制面保存完整配置，数据面 watch 时携带本地 `instance.group/proxyId`，控制面只返回该 group 的 `enabledClusters`、group routing 和可选覆盖项。
- 未配置 `proxyGroups` 时，控制面自动按 `default` group 派生快照，行为兼容当前全局 `backends/routing/limits/governance/analysis`。
- group-scoped snapshot 不包含 `proxyGroups` 本身，数据面收到后仍按普通 `ProxyConfig` 应用；因此无关 Redis clusters 不会被该 group 的 proxy ensure 连接。
- `backends.clusters[].auth` 用于 proxy 到 Redis backend 的认证；`username` 为空时发送 `AUTH <password>`，非空时发送 Redis ACL 形式 `AUTH <username> <password>`。
- `auth.enabled=true` 时 `password` 必填；控制面前端和 YAML 预览必须掩码展示 password。
- `matchAll=true` 用于整集群切换类规则，按首个 key 或 namespace 做稳定分流；`trafficPercent=100` 完成后应改写 `defaultCluster` 并移除临时规则。
- `namespace` 来自连接上 `AUTH <namespace> <token>` 绑定的身份；未认证连接只能命中未设置 namespace 的路由规则。
- `keyPattern` 使用受限 glob 语义，支持 `*` 和 `?`，不是 regex；请求热路径优先使用 `keyPrefix`。
- 控制面生成同结构配置；开启 `controlPlane.enabled` 后，数据面通过 `/api/v1/config/watch` 长轮询消费新快照。
- `controlPlane.pollIntervalSeconds` 在长轮询模式下表示异常后的重试间隔；正常无变更时控制面返回 `204`，数据面立即续订下一次 watch。
- `controlPlane.watchTimeoutSeconds` 表示单次 watch 的服务端等待窗口；`requestTimeoutMillis` 是客户端请求超时余量。
- `instance` 是数据面本地运行身份，不属于控制面下发快照，也不参与 `configHash`。`proxyId` 显式配置时原样使用；为空时由 `group + advertiseIp + 数据面端口` 生成，例如 `frontend-10-0-0-1-6379`。
- `instance.advertiseIp` 优先使用配置值；为空时自动探测本机非 loopback IP。容器或多网卡环境建议由部署系统显式注入。
- `registration` 是数据面本地运行配置，不属于控制面下发快照；启用后数据面主动注册到控制面观测 target，并通过 heartbeat 参与多 proxy 收敛判断。
- `registration.controlPlaneUrl` 推荐指向 `http://host:8090/api/v1`；数据面会向 `/observability/targets` upsert 自身 `proxyId`、`group`、`advertiseIp`、`advertisePort`、`adminUrl`、`dataplane`、`cluster` 和 OpenTelemetry resource 字段。
- 配置发布后，只有控制面 `/api/v1/routes/convergence` 返回 `CONVERGED`，才表示所有已注册数据面都已生效各自 group 对应的最新 `routeEpoch + configHash`。
- `governance.enabled=false` 时数据面保持透明代理；开启后，`AUTH <namespace> <token>` 由 proxy 本地处理并绑定连接身份，不转发给 Redis。
- `governance.requireAuth=true` 时，除 `AUTH` / `QUIT` 外的未认证请求返回 `-NOAUTH Authentication required`。
- namespace 维度支持连接数、秒级 QPS 和 inflight 限制；`0` 表示不限制。
- key 维度支持精确禁用 `disabledKeys` 和按 `keyPrefix` / `hashTag` 匹配的 `keyRules`；`maxQps` 使用本地滑动窗口计数，默认 `1000ms / 100ms`。
- 滑动窗口限流算法的实现、过期 bucket 清理逻辑和 ring buffer 对比见 [Sliding Window Limiter 实现说明](sliding-window-limiter.md)。
- 启用 key 治理时，多 key 命令任意 key 命中禁用或限流都会拒绝整个请求；无法识别 key 位置的命令 fail closed。
- 治理指标按 Go Prometheus 命名和 Java Micrometer 命名分别暴露，语义保持一致：auth、治理拒绝/告警、namespace 当前连接和 inflight、namespace 配置限额和限流拒绝、key rule 决策、key 限额配置和滑动窗口用量。
- namespace token 第一版以明文配置和发布，控制面版本历史会保存完整配置；平台化阶段再接密钥管理。
- `limits.largeResponseBytes`、`analysis.hotKey.*`、`analysis.largeKey.*`、`analysis.slowQuery.*` 均纳入控制面发布模型；数据面接受更大 `routeEpoch` 后会随 route snapshot 生效。
- 所有切换和治理规则必须可审计、可回滚。
