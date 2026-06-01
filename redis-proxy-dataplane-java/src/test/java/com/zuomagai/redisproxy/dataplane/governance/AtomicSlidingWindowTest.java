package com.zuomagai.redisproxy.dataplane.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class AtomicSlidingWindowTest {
    @Test
    void allowsUpToLimitWithCasUnderConcurrency() throws Exception {
        AtomicSlidingWindow window = new AtomicSlidingWindow(100, 10, 1_000);

        int allowed = runConcurrent(64, () -> window.allow(1_000, 8).allowed() ? 1 : 0);

        assertThat(allowed).isEqualTo(8);
        assertThat(window.allow(1_000, 8).total()).isEqualTo(8);
    }

    @Test
    void expiresOldBucketsAfterWindow() {
        AtomicSlidingWindow window = new AtomicSlidingWindow(100, 10, 1_000);

        assertThat(window.allow(1_000, 1).allowed()).isTrue();
        assertThat(window.allow(1_900, 1).allowed()).isFalse();
        assertThat(window.allow(2_100, 1).allowed()).isTrue();
    }

    @Test
    void ignoresFutureBucketsWhenClockMovesBack() {
        AtomicSlidingWindow window = new AtomicSlidingWindow(100, 10, 1_000);

        assertThat(window.allow(1_500, 1).allowed()).isTrue();
        assertThat(window.allow(1_000, 1).allowed()).isTrue();
    }

    @Test
    void reportsWhenTimeCannotBeRepresented() {
        AtomicSlidingWindow window = new AtomicSlidingWindow(100, 10, 1_000);

        assertThat(window.canRepresent(1_000)).isTrue();
        assertThat(window.canRepresent(1_000 + (((long) Integer.MAX_VALUE) + 1) * 100)).isFalse();
    }

    private static int runConcurrent(int tasks, Callable<Integer> task) throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                calls.add(task);
            }
            int total = 0;
            for (Future<Integer> future : executor.invokeAll(calls)) {
                total += future.get();
            }
            return total;
        } finally {
            executor.shutdownNow();
        }
    }
}
