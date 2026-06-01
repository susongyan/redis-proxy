package com.zuomagai.redisproxy.dataplane.governance;

import java.util.concurrent.atomic.AtomicLongArray;

final class AtomicSlidingWindow {
    private static final long EXPIRED_RELATIVE_INDEX = -1L;
    private static final int COUNT_BITS = 32;
    private static final long COUNT_MASK = 0xffff_ffffL;

    final int bucketMillis;
    final int bucketCount;
    private final long baseBucketIndex;
    private final AtomicLongArray buckets;

    AtomicSlidingWindow(int bucketMillis, int bucketCount, long nowMillis) {
        this.bucketMillis = bucketMillis;
        this.bucketCount = Math.max(1, bucketCount);
        this.baseBucketIndex = Math.floorDiv(nowMillis, bucketMillis);
        this.buckets = new AtomicLongArray(this.bucketCount);
        long empty = pack(EXPIRED_RELATIVE_INDEX, 0);
        for (int i = 0; i < this.bucketCount; i++) {
            this.buckets.set(i, empty);
        }
    }

    boolean canRepresent(long nowMillis) {
        long relative = relativeBucket(nowMillis);
        return relative >= 0 && relative <= Integer.MAX_VALUE;
    }

    Result allow(long nowMillis, int limit) {
        if (limit <= 0) {
            return new Result(true, 0);
        }
        long currentRelative = relativeBucket(nowMillis);
        if (currentRelative < 0 || currentRelative > Integer.MAX_VALUE) {
            return new Result(false, totalFor(currentRelative));
        }
        int slot = Math.floorMod(currentRelative, bucketCount);
        while (true) {
            long before = buckets.get(slot);
            long beforeRelative = relativeIndex(before);
            int beforeCount = count(before);
            boolean currentSlot = beforeRelative == currentRelative;
            long next = currentSlot
                    ? pack(currentRelative, beforeCount + 1)
                    : pack(currentRelative, 1);
            int total = totalFor(currentRelative);
            if (total >= limit) {
                return new Result(false, total);
            }
            if (buckets.compareAndSet(slot, before, next)) {
                return new Result(true, total + 1);
            }
            Thread.onSpinWait();
        }
    }

    private int totalFor(long currentRelative) {
        int total = 0;
        for (int i = 0; i < bucketCount; i++) {
            long state = buckets.get(i);
            long relative = relativeIndex(state);
            if (relative >= 0 && currentRelative >= relative && currentRelative - relative < bucketCount) {
                total += count(state);
            }
        }
        return total;
    }

    private long relativeBucket(long nowMillis) {
        return Math.floorDiv(nowMillis, bucketMillis) - baseBucketIndex;
    }

    private static long pack(long relativeIndex, int count) {
        return (relativeIndex << COUNT_BITS) | (count & COUNT_MASK);
    }

    private static long relativeIndex(long state) {
        return state >> COUNT_BITS;
    }

    private static int count(long state) {
        return (int) (state & COUNT_MASK);
    }

    record Result(boolean allowed, int total) {
    }
}
