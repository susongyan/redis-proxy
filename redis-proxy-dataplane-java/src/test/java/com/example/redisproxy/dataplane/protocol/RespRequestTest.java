package com.example.redisproxy.dataplane.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RespRequestTest {
    @Test
    void exposesCommandAndArgumentsFromOffsets() {
        RespRequest request = new RespRequest(
                Unpooled.EMPTY_BUFFER,
                List.of("set".getBytes(StandardCharsets.US_ASCII),
                        "app:{user}:1".getBytes(StandardCharsets.UTF_8),
                        "value".getBytes(StandardCharsets.UTF_8)));
        try {
            assertThat(request.command()).isEqualTo("SET");
            assertThat(request.commandEquals("set")).isTrue();
            assertThat(request.argCount()).isEqualTo(3);
            assertThat(request.argUtf8(1)).isEqualTo("app:{user}:1");
            assertThat(request.arg(1).startsWithUtf8("app:")).isTrue();
            assertThat(request.arg(1).hashTagEqualsUtf8("user")).isTrue();
            assertThat(request.argBytes(2)).isEqualTo("value".getBytes(StandardCharsets.UTF_8));
        } finally {
            request.release();
        }
    }
}
