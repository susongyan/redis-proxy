# 性能专项：协议解析与少拷贝转发优化


当前 Go / Java 数据面都需要先解析 RESP 请求，再执行路由、治理、观测和 backend 转发。这个设计是必要的，因为 proxy 必须识别请求边界、保持 pipeline 顺序、执行 key 级策略、计算 Redis Cluster slot，并在失败时按 sequence 返回占位响应；因此不能退化成完全透明的 TCP pipe。

但当前实现仍存在可优化空间：

- Java `RespRequestDecoder` 当前会为每个 bulk arg 创建 `byte[]`，大 value `SET/MSET` 会产生额外复制。
- Java RESP 长度解析当前会创建短字符串再 `Integer.parseInt`，高 QPS 下会增加分配和 CPU。
- Go `ReadRequest` 当前同时构建 `Raw []byte` 和逐参数 `Args [][]byte`，存在 raw 与 args 双份持有。
- 治理、路由、热 key、大 key、慢查询在部分路径会把 command/key 转成 `String`，在热点 key 和多 key 命令下会放大分配。

优化目标：

1. 将 RESP request parser 分层为 `frame boundary parser`、`command/key offset parser` 和 `按需完整 args materialize`。
2. 请求转发继续使用原始 frame，避免为了转发而复制参数内容。
3. 路由、治理和观测优先使用 `raw buffer + offset/length` 做 byte-level 比较、hashTag 提取和 slot 计算。
4. 只有 debug TopN、报告化输出或必须持久化明细时，才把 key 转成字符串。
5. 保持现有错误语义、pipeline sequence、ASKING/MOVED、namespace/key governance 和观测功能不变。

Java 数据面实施计划：

1. 新增轻量请求模型，例如 `RespRequest(ByteBuf raw, List<ArgRef> args)`，`ArgRef` 只保存 offset、length 和必要的 ASCII command view。
2. `RespRequestDecoder` 改为只扫描 RESP array/bulk 边界，不为每个 arg 分配 `byte[]`。
3. 长度解析改为直接从 `ByteBuf` 读 ASCII 数字，去掉 `toString + Integer.parseInt`。
4. `GovernancePolicy`、`RouteResolver`、`HotKeyTracker`、`LargeKeyTracker`、`SlowQueryTracker` 改为接受 `ArgRef` 或 byte view。
5. key prefix、hashTag、只读命令、deny/warn 命令匹配改为 byte-level fast path，常见命令避免创建临时字符串。
6. 对 `AUTH <namespace> <token>`、debug 明细和控制面报告需要字符串的路径保留按需 materialize。
7. 增加 JMH 或 micro benchmark，对比旧 parser 与 offset parser 的 allocation rate、CPU 和 p99。

Go 数据面实施计划：

1. 将 `protocol.Request` 从 `Raw []byte + Args [][]byte` 演进为 `Raw []byte + ArgRef[]`。
2. RESP parser 只复制或持有完整 raw frame，参数通过 offset 指向 raw frame。
3. command、key、hashTag、slot、prefix 判断基于 raw slice 完成。
4. 热 key / 大 key / 慢查询只在进入 TopN 明细时转换字符串。
5. 使用 Go benchmark 观测 `allocs/op`、`B/op` 和 pipeline 场景下 GC pause 变化。

验收口径：

- Java / Go 数据面现有单测、smoke、governance、dynamic route、ASKING 和 observability E2E 不回退。
- 常见命令 `GET/SET/DEL/MGET/MSET/EXPIRE/HGET/HSET/ZADD` 的 command/key 提取行为与现有实现一致。
- 小 value 高 QPS 场景 allocation 明显下降。
- 大 value `SET/GET` 场景避免因参数复制导致额外内存峰值。
- benchmark 报告中单独标注 `RESP offset parser` 版本，避免和旧基线混淆。
