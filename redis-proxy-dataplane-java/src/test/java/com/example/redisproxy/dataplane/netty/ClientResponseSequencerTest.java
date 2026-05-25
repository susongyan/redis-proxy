package com.example.redisproxy.dataplane.netty;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.redisproxy.dataplane.netty.ClientResponseSequencer.PendingResponse;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientResponseSequencerTest {
    @Test
    void flushesResponsesBySequenceOrder() {
        ClientResponseSequencer sequencer = new ClientResponseSequencer();
        long first = sequencer.nextSequence();
        long second = sequencer.nextSequence();
        List<String> flushed = new ArrayList<>();

        sequencer.complete(second, response("second"), pending ->
                flushed.add(pending.response().toString(StandardCharsets.US_ASCII)));
        assertThat(flushed).isEmpty();
        assertThat(sequencer.pendingResponses()).isEqualTo(1);

        sequencer.complete(first, response("first"), pending ->
                flushed.add(pending.response().toString(StandardCharsets.US_ASCII)));

        assertThat(flushed).containsExactly("first", "second");
        assertThat(sequencer.pendingResponses()).isZero();
    }

    private static PendingResponse response(String value) {
        return new PendingResponse(Unpooled.copiedBuffer(value, StandardCharsets.US_ASCII), null, "GET", null, null, null, 0, 0);
    }
}
