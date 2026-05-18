package com.example.redisproxy.dataplane.netty;

import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class ClientResponseSequencer {
    private long nextSequence;
    private long nextFlushSequence;
    private final Map<Long, PendingResponse> pending = new TreeMap<>();

    public long nextSequence() {
        return nextSequence++;
    }

    public void complete(long sequence, PendingResponse response, Consumer<PendingResponse> flusher) {
        pending.put(sequence, response);
        while (true) {
            PendingResponse next = pending.remove(nextFlushSequence);
            if (next == null) {
                return;
            }
            nextFlushSequence++;
            flusher.accept(next);
        }
    }

    public int pendingResponses() {
        return pending.size();
    }

    public record PendingResponse(ByteBuf response, Throwable error, String command, io.micrometer.core.instrument.Timer.Sample sample) {}
}
