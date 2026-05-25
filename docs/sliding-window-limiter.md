# Sliding Window Limiter 实现说明

本文记录当前 Java 数据面治理限流使用的滑动窗口算法，以及与后续可优化的 `ring buffer + atomic total` 方案的对比。

## 当前实现

当前实现位于 Java 数据面 `AtomicSlidingWindow`，用于：

- namespace QPS 限流
- key rule QPS 限流

它本质上是一个 **CAS 版 ring buffer sliding window**。

核心结构：

```text
AtomicLongArray buckets
bucket slot = currentRelativeBucket % bucketCount
bucket state = packed(relativeBucketIndex, count)
```

每个 bucket 使用一个 `long` 保存两部分信息：

```text
高 32 bit: relativeBucketIndex
低 32 bit: count
```

窗口创建时记录 `baseBucketIndex`：

```text
baseBucketIndex = nowMillis / bucketMillis
relativeBucketIndex = nowMillis / bucketMillis - baseBucketIndex
```

这样可以避免直接把 epoch bucket index 打进 `int` 导致溢出。

## 请求处理流程

每次请求进入限流时：

1. 计算当前 bucket：

   ```text
   currentRelative = nowMillis / bucketMillis - baseBucketIndex
   slot = currentRelative % bucketCount
   ```

2. 遍历所有 bucket，统计当前滑动窗口内的 total：

   ```text
   bucket still valid when:
   bucketRelative >= 0
   currentRelative >= bucketRelative
   currentRelative - bucketRelative < bucketCount
   ```

3. 如果 total 已经达到 `maxQps`，拒绝当前请求。

4. 如果 slot 内 bucket 不是当前 bucket，说明该 slot 存的是旧 bucket：

   ```text
   CAS oldState -> pack(currentRelative, 1)
   ```

5. 如果 slot 内 bucket 是当前 bucket：

   ```text
   CAS oldState -> pack(currentRelative, oldCount + 1)
   ```

6. CAS 失败说明有并发请求更新了同一 slot，当前请求重新读取并重试。

该实现不使用 `synchronized`，也不使用 `LongAdder` 近似统计，限流结果保持精确。

## 过期 Bucket 清理逻辑

当前实现没有后台清理线程，过期 bucket 采用 **惰性清理**。

清理分两层：

### 逻辑清理

每次统计 total 时，窗口外 bucket 会被直接忽略：

```text
currentRelative - bucketRelative >= bucketCount
```

因此，过期 bucket 即使仍保留在 `AtomicLongArray` 中，也不会影响限流结果。

### 物理清理

当新的请求命中同一个 ring slot 时，旧 bucket 会被 CAS 覆盖：

```text
old bucket: pack(oldRelative, oldCount)
new bucket: pack(currentRelative, 1)
```

也就是说，过期 bucket 只有在该 slot 被复用时才会被物理清理。

由于 `AtomicLongArray` 的长度固定，过期 bucket 不会造成内存增长。

## 当前方案优点

- 无 JVM monitor lock，避免 `synchronized` 带来的 event loop 阻塞。
- 精确限流，不会因为近似计数造成明显超卖。
- 内存固定，复杂度可控。
- 实现相对简单，适合当前默认配置：

  ```text
  keyLimitWindowMillis = 1000
  keyLimitBucketMillis = 100
  bucketCount = 10
  ```

## 当前方案代价

当前实现每次请求都需要遍历所有 bucket 计算 total：

```text
time complexity = O(bucketCount)
```

当 bucket 数较小时，例如 `10` 个 bucket，该成本可以接受。

如果后续配置变成下面这种更细粒度或更长窗口，CPU 成本会明显升高：

```text
60s / 100ms = 600 buckets
10s / 10ms = 1000 buckets
60s / 10ms = 6000 buckets
```

热点 key 高并发场景下，CAS 自旋重试也会增加 CPU 消耗。它不会阻塞线程，但会消耗 event loop CPU。

## 与 Ring Buffer + Atomic Total 对比

后续可升级为：

```text
AtomicLongArray buckets
AtomicInteger total
```

请求路径不再每次遍历所有 bucket，而是：

1. 读取 `total` 判断是否超限。
2. 命中当前 bucket 时 CAS count + 1，同时 CAS 或原子增加 total。
3. bucket 过期复用时，将旧 bucket count 从 total 扣除。

### 优点

- 请求路径接近 `O(1)`。
- bucket 数变大时性能更稳定。
- 热 key 高并发时 CPU 开销更低。

### 难点

需要严格处理 bucket rollover 和 total 扣减，否则容易出现：

- 少扣旧 bucket，导致误拒。
- 多扣旧 bucket，导致超卖。
- 并发 rollover 时重复扣减。
- 时钟回拨或大幅跳跃时 total 与 bucket 不一致。

因此，`ring buffer + atomic total` 的测试复杂度更高，需要覆盖高并发 rollover、时间跳变、配置热更新和窗口重建。

## 结论

当前 CAS sliding window 是一个合适的阶段性实现：

- 对默认 `1s / 100ms` 小窗口足够合理。
- 已消除全局锁和 per-window monitor lock。
- 保持精确限流语义。
- 实现复杂度和正确性风险可控。

当出现以下条件时，再升级到 `ring buffer + atomic total`：

- 单规则 bucket 数超过数百。
- 热 key 或热点 namespace 限流成为 CPU 瓶颈。
- Java 数据面 governance benchmark 显示 limiter 占据明显 p99/p999 成本。
- 需要支持更长窗口或更细 bucket。
