# Redis Proxy 命令支持清单

本文档描述当前 Go / Java 数据面的 Redis 命令支持口径。这里的“支持”分为两层：

- **透明转发支持**：proxy 能解析 RESP2 请求边界并转发到后端 Redis。
- **治理识别支持**：proxy 能识别 command/key 位置，用于 Redis Cluster 路由、namespace/key 治理、热 key、大 key、慢查询归因。

当前数据面不是严格白名单代理。除默认治理拦截或配置拒绝的命令外，RESP2 array/bulk 格式命令通常会被转发。但如果命令不在 key 识别清单内，启用 allowed key prefix、disabled key、key rule 限流等 key 治理能力时会 fail closed，返回 `-ERR command key policy unsupported`，避免绕过治理。

## 本地处理命令

这些命令由 proxy 本地处理，不直接透传给业务后端 Redis：

| 命令 | 说明 |
| --- | --- |
| `AUTH <namespace> <token>` | proxy 本地 namespace 鉴权，成功后绑定当前 client connection 身份。 |
| `ASKING` | proxy 处理 Redis Cluster `ASK` 重定向时内部发送给临时 owner。 |
| `CLUSTER SLOTS` | proxy 内部用于刷新 Redis Cluster slot cache。 |

`QUIT` 在治理判断中允许通过，不要求认证。

## 默认治理策略

全局默认拒绝命令：

```text
FLUSHALL
FLUSHDB
CONFIG
SHUTDOWN
DEBUG
MODULE
```

全局默认告警但不拒绝命令：

```text
KEYS
EVAL
SCRIPT
```

这些默认值可以通过 `governance.commandPolicy.deniedCommands` 和 `governance.commandPolicy.warnOnlyCommands` 调整，也可以在 namespace 维度追加覆盖。

## 只读 Namespace 允许命令

当 namespace 配置 `readOnly=true` 时，当前只允许以下读命令：

```text
GET
EXISTS
TTL
PTTL
MGET
HGET
SMEMBERS
ZRANGE
PING
```

未列入清单的命令在只读 namespace 下按写风险处理并拒绝。

## Key 识别清单

以下命令已支持 key 位置识别，可用于：

- Redis Cluster slot 计算和 backend affinity。
- `allowedKeyPrefixes`。
- `disabledKeys`。
- `keyRules` 禁用和滑动窗口限流。
- 热 key / 大 key / 慢查询本地 TopN 归因。

### 单 Key 命令

这些命令使用第一个参数作为 key：

```text
GET
SET
EXPIRE
PEXPIRE
TTL
PTTL
HGET
HSET
HDEL
LPUSH
RPUSH
LPOP
RPOP
SADD
SREM
SMEMBERS
ZADD
ZREM
ZRANGE
```

### 多 Key 命令

这些命令会提取多个 key：

| 命令 | Key 解析规则 |
| --- | --- |
| `DEL` | 第 1 个参数之后全部视为 key。 |
| `EXISTS` | 第 1 个参数之后全部视为 key。 |
| `MGET` | 第 1 个参数之后全部视为 key。 |
| `MSET` | 从第 1 个参数开始按 `key value` 成对解析 key。参数数量不合法时视为不支持。 |

多 key 命令任意 key 命中禁用或限流时，整个请求会被拒绝。

## 路由规则匹配口径

当前 route rule 支持以下匹配条件：

```text
namespace
keyPrefix
keyPattern
hashTag
matchAll
trafficPercent
```

说明：

- `namespace` 使用当前连接通过 `AUTH <namespace> <token>` 绑定的身份。
- `keyPrefix` / `keyPattern` / `hashTag` 当前基于请求首个 key 判断。
- `keyPattern` 是受限 glob 语义，支持 `*` 和 `?`，不是正则表达式。
- `matchAll=true` 常用于整集群切换，按首个 key 或 namespace 做稳定百分比分流。
- `trafficPercent` 使用稳定 hash 计算，保证同 key 在同配置版本下稳定命中同一侧。

## 不支持或暂不建议使用的命令

以下命令不在当前生产推荐使用范围内。部分命令在治理未拦截时可能会被透明转发，但 proxy 未提供完整语义保证，因此应视为**不支持或暂不建议使用**。

### 事务类

```text
MULTI
EXEC
WATCH
DISCARD
UNWATCH
```

限制：

- 需要将 client connection 与 backend connection 绑定，保证事务上下文不被连接池复用打散。
- 需要处理事务期间路由固定、错误占位、pipeline 顺序和连接异常恢复。
- 当前未实现完整 connection pinning 和事务状态机。

### Pub/Sub 类

```text
SUBSCRIBE
PSUBSCRIBE
PUBLISH
UNSUBSCRIBE
PUNSUBSCRIBE
PUBSUB
```

限制：

- `SUBSCRIBE` / `PSUBSCRIBE` 会让连接进入订阅模式，需要独占后端连接和特殊响应循环。
- 当前数据面以请求/响应转发模型为主，未实现订阅连接生命周期治理。
- `PUBLISH` 在 cluster 下还涉及广播或目标节点选择语义，当前未做聚合。

### Lua / Script 类

```text
EVAL
EVALSHA
SCRIPT
FUNCTION
FCALL
FCALL_RO
```

限制：

- `EVAL` / `EVALSHA` 需要解析 `numkeys` 和 `KEYS[]`，并做 slot 一致性校验。
- 当前默认对 `EVAL` / `SCRIPT` 只做 warn 打点，不按 `KEYS[]` 做精确路由。
- Redis 7 `FUNCTION` / `FCALL` 系列暂未建模治理语义。

### Scan / Cursor 类

```text
SCAN
HSCAN
SSCAN
ZSCAN
```

限制：

- Redis Cluster 下 `SCAN` 需要跨节点 cursor 聚合。
- `HSCAN` / `SSCAN` / `ZSCAN` 是单 key 命令，但当前 key 识别清单未覆盖，启用 key 治理时会 fail closed。
- 当前未实现跨节点游标状态和结果聚合。

### 连接状态 / 协议协商类

```text
CLIENT
SELECT
HELLO
RESET
READONLY
READWRITE
```

限制：

- `SELECT` 会改变 Redis 连接 DB 上下文，在 backend 连接复用场景下可能污染其他 client 请求。
- `CLIENT SETNAME`、`CLIENT TRACKING` 等命令与 proxy 多路复用连接语义不一致。
- `HELLO` / RESP3 当前未完整支持，数据面按 RESP2 请求/响应模型处理。
- `READONLY` / `READWRITE` 涉及 Redis Cluster replica read 语义，当前路由模型未支持读写分离。

### 管理 / 高风险类

默认治理会拒绝以下命令：

```text
FLUSHALL
FLUSHDB
CONFIG
SHUTDOWN
DEBUG
MODULE
```

以下管理类命令当前也不建议通过 proxy 暴露给业务侧，生产上应通过治理配置显式拒绝：

```text
BGSAVE
BGREWRITEAOF
SAVE
SLAVEOF
REPLICAOF
MIGRATE
RESTORE
RESTORE-ASKING
ACL
MONITOR
SLOWLOG
LATENCY
MEMORY
INFO
ROLE
CLUSTER
COMMAND
DBSIZE
LASTSAVE
```

### Blocking 命令

```text
BLPOP
BRPOP
BRPOPLPUSH
BZPOPMIN
BZPOPMAX
XREAD
XREADGROUP
```

限制：

- blocking 命令会长时间占用 backend connection，影响连接池公平性和 pipeline 排队。
- 当前未实现 blocking 命令隔离池、超时治理和取消语义。

### Stream 命令

```text
XADD
XRANGE
XREVRANGE
XLEN
XACK
XGROUP
XINFO
XPENDING
XCLAIM
XAUTOCLAIM
XTRIM
XDEL
```

限制：

- 当前 key 识别清单未覆盖 Stream 命令。
- 启用 key prefix / key rule 治理时会按不支持 key policy 处理。
- 如需支持，建议先补齐 Stream key 解析、只读/写命令分类和慢查询/大 key 归因语义。

### GEO / Bitmap / HyperLogLog / BitField 命令

```text
GEOADD
GEODIST
GEOHASH
GEOPOS
GEORADIUS
GEOSEARCH
PFADD
PFCOUNT
PFMERGE
SETBIT
GETBIT
BITCOUNT
BITOP
BITPOS
BITFIELD
BITFIELD_RO
```

限制：

- 当前 key 识别清单未覆盖这些命令。
- 未配置 key 治理时可能透明转发；启用 key 治理时会 fail closed。
- 需要补齐 key 位置、只读/写分类和 cluster 路由测试后再作为正式支持命令。

## Redis Cluster 行为

当前 Redis Cluster 支持：

- `CLUSTER SLOTS` 刷新 slot cache。
- `MOVED`：更新单 slot，并触发限频全量刷新。
- `ASK`：执行一次透明 `ASKING + 原始请求` 临时路由，不污染长期 slot cache。
- backend TCP 断开不修改 slot owner，只让对应 node/slot 请求快速失败并后台退避重连。

尚未完整支持：

- 多 key 跨 slot scatter-gather 聚合。
- Lua 脚本按 `KEYS[]` 路由。
- 集群级 `SCAN` 聚合。

## 当前建议

生产接入初期建议优先使用以下命令族：

- String：`GET`、`SET`、`MGET`、`MSET`、`DEL`、`EXISTS`、`EXPIRE`、`PEXPIRE`、`TTL`、`PTTL`
- Hash：`HGET`、`HSET`、`HDEL`
- List：`LPUSH`、`RPUSH`、`LPOP`、`RPOP`
- Set：`SADD`、`SREM`、`SMEMBERS`
- ZSet：`ZADD`、`ZREM`、`ZRANGE`

需要使用 Lua、事务、Pub/Sub、SCAN 或复杂多 key 聚合时，应先明确对应 Redis Cluster 语义和 proxy 治理策略，再作为专项能力补齐。
