package com.zuomagai.redisproxy.dataplane.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.zuomagai.redisproxy.dataplane.config.ProxyProperties;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BackendPoolTest {
    @Test
    void reconnectingMetricsDoNotBecomeNegative() throws Exception {
        BackendPool pool = new BackendPool(new ProxyProperties(), new SimpleMeterRegistry());
        try {
            Method decrement = BackendPool.class.getDeclaredMethod("decrementReconnecting", String.class);
            decrement.setAccessible(true);

            decrement.invoke(pool, "127.0.0.1:7100");
            decrement.invoke(pool, "127.0.0.1:7100");

            assertThat(pool.reconnectingCount()).isZero();
            assertThat(pool.nodeReconnectingCount("127.0.0.1:7100")).isZero();
        } finally {
            pool.close();
        }
    }

    @Test
    void ensureAllDoesNotRebuildExistingBackendPool() throws Exception {
        try (ServerSocket first = new ServerSocket(0); ServerSocket second = new ServerSocket(0)) {
            acceptAndHold(first);
            acceptAndHold(second);
            String firstAddress = "127.0.0.1:" + first.getLocalPort();
            String secondAddress = "127.0.0.1:" + second.getLocalPort();
            BackendPool pool = new BackendPool(new ProxyProperties(), new SimpleMeterRegistry());
            try {
                pool.ensureAll(List.of(firstAddress));
                assertThat(pool.desiredCount(firstAddress)).isEqualTo(1);

                pool.ensureAll(List.of(firstAddress, secondAddress));

                assertThat(pool.desiredCount(firstAddress)).isEqualTo(1);
                assertThat(pool.desiredCount(secondAddress)).isEqualTo(1);
            } finally {
                pool.close();
            }
        }
    }

    @Test
    void askingRequestSkipsAskingResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<List<String>> frames = new CompletableFuture<>();
            Thread backend = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                    String first = readRespArrayRaw(in);
                    socket.getOutputStream().write("+OK\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    String second = readRespArrayRaw(in);
                    socket.getOutputStream().write("$3\r\nbar\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    frames.complete(List.of(first, second));
                } catch (Exception e) {
                    frames.completeExceptionally(e);
                }
            });
            backend.setDaemon(true);
            backend.start();

            ProxyProperties properties = properties("127.0.0.1:" + server.getLocalPort());
            BackendPool pool = new BackendPool(properties, new SimpleMeterRegistry());
            try {
                ByteBuf request = Unpooled.copiedBuffer("*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n", StandardCharsets.US_ASCII);
                ByteBuf response = pool.doRequestWithAsking("127.0.0.1:" + server.getLocalPort(), request, 0).get(1, TimeUnit.SECONDS);
                try {
                    assertThat(response.toString(StandardCharsets.US_ASCII)).isEqualTo("$3\r\nbar\r\n");
                } finally {
                    request.release();
                    response.release();
                }

                assertThat(frames.get(1, TimeUnit.SECONDS)).containsExactly(
                        "*1\r\n$6\r\nASKING\r\n",
                        "*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n");
            } finally {
                pool.close();
            }
        }
    }

    private static ProxyProperties properties(String node) {
        ProxyProperties properties = new ProxyProperties();
        ProxyProperties.Cluster cluster = new ProxyProperties.Cluster();
        cluster.setName("redis-a");
        cluster.setNodes(List.of(node));
        cluster.getPool().setConnectionsPerNode(1);
        cluster.getPool().setMaxInflightPerConnection(8);
        properties.getBackends().setClusters(List.of(cluster));
        properties.getRouting().setDefaultCluster("redis-a");
        return properties;
    }

    private static void acceptAndHold(ServerSocket server) {
        Thread backend = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread holder = new Thread(() -> {
                        try (socket) {
                            Thread.sleep(TimeUnit.SECONDS.toMillis(5));
                        } catch (Exception ignored) {
                        }
                    });
                    holder.setDaemon(true);
                    holder.start();
                } catch (Exception ignored) {
                    return;
                }
            }
        });
        backend.setDaemon(true);
        backend.start();
    }

    private static String readRespArrayRaw(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String first = readLine(in, out);
        int count = Integer.parseInt(first.substring(1));
        for (int i = 0; i < count; i++) {
            String bulk = readLine(in, out);
            int size = Integer.parseInt(bulk.substring(1));
            byte[] value = in.readNBytes(size + 2);
            out.write(value);
        }
        return out.toString(StandardCharsets.US_ASCII);
    }

    private static String readLine(BufferedInputStream in, ByteArrayOutputStream out) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = in.read();
            if (current < 0) {
                throw new IllegalStateException("unexpected EOF");
            }
            out.write(current);
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            line.write(current);
            previous = current;
        }
    }
}
