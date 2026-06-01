package com.zuomagai.redisproxy.dataplane.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RespRequestDecoderMicroBenchmarkTest {
    @Test
    void reportsParserCostForCommonFrames() {
        Result get = runCase("GET", frame("GET", "app-a:1"), 1000);
        Result largeSet = runCase("SET-large", frame("SET", "app-a:large", "x".repeat(1024 * 1024)), 50);

        System.out.printf("RESP parser microbenchmark: %s ns/op=%d alloc/op=%d bytes%n",
                get.name(), get.nanosPerOp(), get.allocatedBytesPerOp());
        System.out.printf("RESP parser microbenchmark: %s ns/op=%d alloc/op=%d bytes%n",
                largeSet.name(), largeSet.nanosPerOp(), largeSet.allocatedBytesPerOp());

        assertThat(get.decodedArgs()).isEqualTo(2000);
        assertThat(largeSet.decodedArgs()).isEqualTo(150);
    }

    private static Result runCase(String name, byte[] frame, int iterations) {
        for (int i = 0; i < 100; i++) {
            decodeOnce(frame);
        }
        long allocatedBefore = allocatedBytes();
        long started = System.nanoTime();
        int decodedArgs = 0;
        for (int i = 0; i < iterations; i++) {
            decodedArgs += decodeOnce(frame);
        }
        long elapsed = System.nanoTime() - started;
        long allocatedAfter = allocatedBytes();
        long allocated = allocatedBefore < 0 || allocatedAfter < 0 ? -1 : allocatedAfter - allocatedBefore;
        return new Result(name, elapsed / iterations, allocated < 0 ? -1 : allocated / iterations, decodedArgs);
    }

    private static int decodeOnce(byte[] frame) {
        EmbeddedChannel channel = new EmbeddedChannel(new RespRequestDecoder(frame.length + 64));
        channel.writeInbound(Unpooled.wrappedBuffer(frame));
        RespRequest request = channel.readInbound();
        try {
            return request.argCount();
        } finally {
            request.release();
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] frame(String... args) {
        StringBuilder builder = new StringBuilder();
        builder.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            builder.append('$').append(bytes.length).append("\r\n");
            builder.append(arg).append("\r\n");
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean threadBean
                && threadBean.isThreadAllocatedMemorySupported()) {
            if (!threadBean.isThreadAllocatedMemoryEnabled()) {
                threadBean.setThreadAllocatedMemoryEnabled(true);
            }
            return threadBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    private record Result(String name, long nanosPerOp, long allocatedBytesPerOp, int decodedArgs) {
    }
}
