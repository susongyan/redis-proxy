package com.example.redisproxy.dataplane.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RespValueParserTest {
    @Test
    void parsesClusterSlotsShape() {
        RespValue value = RespValueParser.parse(Unpooled.copiedBuffer(
                "*1\r\n*3\r\n:0\r\n:42\r\n*2\r\n$9\r\n127.0.0.1\r\n:7000\r\n",
                StandardCharsets.US_ASCII));

        assertThat(value.kind()).isEqualTo(RespValue.Kind.ARRAY);
        RespValue range = value.array().getFirst();
        assertThat(range.array().get(0).integer()).isZero();
        assertThat(range.array().get(1).integer()).isEqualTo(42);
        assertThat(new String(range.array().get(2).array().get(0).bytes(), StandardCharsets.US_ASCII))
                .isEqualTo("127.0.0.1");
        assertThat(range.array().get(2).array().get(1).integer()).isEqualTo(7000);
    }
}
