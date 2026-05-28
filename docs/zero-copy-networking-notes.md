# TCP 应用与消息队列零拷贝说明

本文记录 Redis Proxy 设计讨论中的扩展知识点：Java Netty / Go TCP 应用中的数据读取、Kafka / RocketMQ 常说的零拷贝，以及 Linux 上 `sendfile`、`mmap`、`splice`、`MSG_ZEROCOPY` 等能力的适用边界。

## 1. TCP 读取路径

无论 Java Netty 还是 Go `net`，当应用代码能看到 TCP payload 时，数据通常已经从内核态 socket receive buffer 拷贝到了用户态内存。

典型路径：

```text
NIC DMA -> kernel socket receive buffer -> read/recv 系统调用 -> 用户态 buffer
```

Java Netty：

- 底层通过 `epoll`、`kqueue` 或 Java NIO 监听 fd 可读。
- 可读后执行 `read/recv`，把数据读入 Netty `ByteBuf`。
- 如果使用 direct `ByteBuf`，数据位于 JVM 进程的堆外用户态内存。
- 如果使用 heap `ByteBuf`，数据位于 Java heap。
- Netty 常说的零拷贝主要是用户态内部的 `slice`、`duplicate`、`CompositeByteBuf`，减少 `ByteBuf` 之间的二次复制，不是消除 kernel -> user 这次 TCP 读取拷贝。

Go：

- runtime netpoll 监听 fd 可读。
- `conn.Read(buf)` 触发 `read/recv`，内核把数据拷贝到 Go 提供的 `[]byte`。
- `[]byte` 可能在栈上，也可能逃逸到 Go heap。
- 如果后续再构造 `raw bytes.Buffer`、`args [][]byte`，就是用户态内额外复制。

因此对 Redis Proxy 这类 L7 协议代理来说，优化重点不是消除 kernel -> user 读取拷贝，而是减少用户态内部的二次复制、对象分配和字符串化。

## 2. Kafka / RocketMQ 的零拷贝

Kafka / RocketMQ 常说的零拷贝，主要发生在 broker 把已经落盘或已经在 page cache 中的消息发送给 consumer 时。

传统文件发送路径：

```text
disk -> kernel page cache -> user buffer -> kernel socket buffer -> NIC
```

`sendfile` / `FileChannel.transferTo` 路径：

```text
disk -> kernel page cache -> kernel socket buffer / DMA descriptor -> NIC
```

这个优化减少了：

- kernel -> user copy
- user -> kernel copy
- 用户态大 buffer 分配
- CPU cache 污染
- 部分上下文切换和系统调用开销

它成立的前提是：消息内容已经在 commit log / segment 文件或 page cache 中，broker 发送时不需要在用户态解析或修改 payload。

这和 Redis Proxy 的请求路径不同。Redis Proxy 收到的是网络请求，必须读取 payload 才能做：

- RESP frame 边界识别
- Redis Cluster slot 路由
- namespace 鉴权
- 命令治理
- key 级限流和禁用
- 热 key / 大 key / 慢查询归因
- pipeline sequence 和失败占位响应

因此 Redis Proxy 不能直接套用 Kafka / RocketMQ 的文件发送零拷贝模型。

## 3. TCP 应用可用的类零拷贝能力

Linux 提供了一些可用于网络场景的类零拷贝能力，但适用范围有限。

### sendfile

`sendfile` 用于文件到 socket：

```text
file fd -> socket fd
```

适合：

- Kafka / RocketMQ consumer 拉取消息。
- Nginx 静态文件发送。
- 其他文件内容直接发送到网络的场景。

不适合：

- socket -> 应用解析 -> socket 的 Redis Proxy 主请求路径。

### mmap

`mmap` 用于把文件映射到进程虚拟地址空间，减少 `read` 到用户 buffer 的复制。

适合：

- 文件索引。
- commit log / segment 读取。
- 本地缓存文件访问。

不适合：

- 常规 TCP socket receive。应用通常不能直接 `mmap` kernel socket receive buffer。

### splice / tee

`splice` 可以通过 pipe 在 fd 之间搬运数据，尽量避免进入用户态：

```text
socket fd -> pipe -> socket fd
file fd -> pipe -> socket fd
socket fd -> pipe -> file fd
```

适合：

- L4 TCP 透明转发。
- 不需要查看 payload 的 fd-to-fd 搬运。

不适合：

- 需要解析 Redis command/key 的 L7 proxy。

`tee` 可复制 pipe buffer 引用，通常配合 `splice` 做内核态流量复制，工程复杂度更高。

### io_uring

`io_uring` 能减少系统调用和调度开销，并支持 registered buffers、fixed files、zero-copy send 等能力。

适合：

- 高性能异步 I/O 框架。
- C/C++/Rust 等可深度控制 runtime 的网络服务。

限制：

- socket receive 到用户态 buffer 这件事通常仍然存在。
- Go / Java 主流 runtime 接入成本较高。
- 对 Redis Proxy 来说，需要和协议解析、连接池、backpressure 一起评估，不是单点替换。

### MSG_ZEROCOPY

`MSG_ZEROCOPY` 优化 socket send 方向，减少用户态 buffer 到 kernel socket buffer 的复制。

适合：

- 大块 payload send。
- 对 completion/error queue 有明确处理能力的高性能网络服务。

限制：

- 工程复杂，需要处理发送完成通知。
- 小 Redis 命令收益有限。
- 对大 value response 可能有理论价值，但需要压测验证。

### AF_XDP / DPDK

AF_XDP / DPDK 允许绕过传统内核网络栈，把包直接放到用户态可控内存中。

适合：

- 专用高性能网关。
- L4/L7 网络设备。
- 极致性能场景。

限制：

- 需要自己处理大量网络栈、调度和运维复杂度。
- 对通用 Redis Proxy 通常不是第一选择。

## 4. 对 Redis Proxy 的设计结论

Redis Proxy 是 L7 协议代理，必须读取并理解请求内容。对当前项目来说，现实优化路线是：

1. 不追求完全消除 TCP kernel -> user 读取拷贝。
2. 优先减少用户态内部复制：
   - Java 避免 `ByteBuf -> byte[] args`。
   - Go 避免 `raw buffer + args copy` 双份持有。
3. 使用 `raw frame + arg offset/length` 的 shallow parser。
4. 转发继续使用原始 frame buffer，减少为了转发而复制 payload。
5. command/key 尽量 byte-level 比较，只有 debug、报告和持久化明细需要时再字符串化。
6. 大 value response 可以单独研究 send 方向优化，但必须和 Netty/Go runtime、TLS、backpressure 和错误处理一起压测。
7. 如果未来需要纯透明 L4 转发模式，可以单独实现 `splice` fast path，但这个模式不能支持 Redis Cluster key 路由、治理、热 key、大 key和慢查询。

一句话总结：

```text
Kafka / RocketMQ 零拷贝主要优化“文件/page cache -> socket”发送路径；
Redis Proxy 主要瓶颈是“socket -> 用户态解析 -> socket”过程中的用户态二次复制和对象分配。
```
