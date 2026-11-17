package tech.xingyunqi.template.infrastructure.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobLimiterTest {

    @Test
    @DisplayName("同时执行的任务数不超过 concurrency，其余任务阻塞等待")
    void limitsConcurrency() throws Exception {
        JobLimiter limiter = new JobLimiter(2, 100, Duration.ofSeconds(1));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    limiter.run(() -> {
                        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        running.decrementAndGet();
                    });
                    return null;
                }));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(running.get()).isEqualTo(2);
            release.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(peak.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("窗口内开始的任务达到上限后，后续任务等到最早的任务移出窗口才开始")
    void limitsRate() throws Exception {
        Duration window = Duration.ofMillis(300);
        JobLimiter limiter = new JobLimiter(10, 3, window);
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        long begin = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limiter.run(() -> starts.add(System.nanoTime() - begin));
        }
        assertThat(starts.subList(0, 3)).allSatisfy(start -> assertThat(start).isLessThan(window.toNanos()));
        long tolerance = Duration.ofMillis(20).toNanos();
        assertThat(starts.get(3) - starts.get(0)).isGreaterThanOrEqualTo(window.toNanos() - tolerance);
        assertThat(starts.get(4) - starts.get(1)).isGreaterThanOrEqualTo(window.toNanos() - tolerance);
    }

    @Test
    @DisplayName("任务异常原样抛出并释放并发名额")
    void releasesSlotOnFailure() throws Exception {
        JobLimiter limiter = new JobLimiter(1, 10, Duration.ofSeconds(1));
        assertThatThrownBy(() -> limiter.run(() -> {
                    throw new IllegalStateException("boom");
                }))
                .hasMessage("boom");
        AtomicInteger ran = new AtomicInteger();
        limiter.run(ran::incrementAndGet);
        assertThat(ran.get()).isEqualTo(1);
    }
}
