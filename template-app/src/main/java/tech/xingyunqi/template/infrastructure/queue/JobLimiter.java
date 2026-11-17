package tech.xingyunqi.template.infrastructure.queue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 进程内的 BullMQ {@code concurrency} 与 {@code limiter {max, duration}}：信号量 + 滑动窗口计数，超限时阻塞等待。 */
public final class JobLimiter {

    private final Semaphore slots;
    private final int maxJobs;
    private final long windowNanos;
    private final ReentrantLock rateLock = new ReentrantLock(true);
    private final ArrayDeque<Long> started = new ArrayDeque<>();

    public JobLimiter(int concurrency, int maxJobs, Duration window) {
        this.slots = new Semaphore(concurrency, true);
        this.maxJobs = maxJobs;
        this.windowNanos = window.toNanos();
    }

    public void run(Runnable job) throws InterruptedException {
        slots.acquire();
        try {
            awaitRate();
            job.run();
        } finally {
            slots.release();
        }
    }

    private void awaitRate() throws InterruptedException {
        rateLock.lockInterruptibly();
        try {
            while (true) {
                long now = System.nanoTime();
                while (!started.isEmpty() && now - started.getFirst() >= windowNanos) {
                    started.removeFirst();
                }
                if (started.size() < maxJobs) {
                    started.addLast(now);
                    return;
                }
                TimeUnit.NANOSECONDS.sleep(windowNanos - (now - started.getFirst()));
            }
        } finally {
            rateLock.unlock();
        }
    }
}
