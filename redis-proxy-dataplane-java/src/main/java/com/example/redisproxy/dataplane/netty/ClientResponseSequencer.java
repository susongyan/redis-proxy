package com.example.redisproxy.dataplane.netty;

import io.netty.buffer.ByteBuf;
import com.example.redisproxy.dataplane.analysis.LargeKeyTracker;
import com.example.redisproxy.dataplane.analysis.SlowQueryTracker;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;

public final class ClientResponseSequencer {
    private long nextSequence;
    private long nextFlushSequence;
    private final Map<Long, PendingResponse> pending = new TreeMap<>();

    public long nextSequence() {
        return nextSequence++;
    }

    public Result complete(long sequence, PendingResponse response) {
        boolean blocked = sequence > nextFlushSequence;
        pending.put(sequence, response);
        List<PendingResponse> flushed = new ArrayList<>();
        while (true) {
            PendingResponse next = pending.remove(nextFlushSequence);
            if (next == null) {
                return new Result(flushed, blocked, pending.size());
            }
            nextFlushSequence++;
            flushed.add(next);
        }
    }

    public int pendingResponses() {
        return pending.size();
    }

    public record PendingResponse(ByteBuf response, Throwable error, String command, io.micrometer.core.instrument.Timer.Sample sample, LargeKeyTracker.Context largeKeyContext, SlowQueryTracker.Context slowQueryContext, long startNanos, long backendNanos, long completedNanos) {
        public PendingResponse(ByteBuf response, Throwable error, String command, io.micrometer.core.instrument.Timer.Sample sample, LargeKeyTracker.Context largeKeyContext, SlowQueryTracker.Context slowQueryContext, long startNanos, long backendNanos) {
            this(response, error, command, sample, largeKeyContext, slowQueryContext, startNanos, backendNanos, System.nanoTime());
        }
    }

    public record Result(List<PendingResponse> flushed, boolean blocked, int pendingResponses) {}
}
