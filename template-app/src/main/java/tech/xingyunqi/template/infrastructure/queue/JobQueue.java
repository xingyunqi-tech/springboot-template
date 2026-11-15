package tech.xingyunqi.template.infrastructure.queue;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 队列（nestjs BullMQ 队列名）：任务以 {@code @Job(labels = <队列名>)} 归属队列，共 {@code attempts} 次尝试，第 n 次失败后等待 {@code delay ×
 * 2^(n-1)}（BullMQ {@code exponential}）。不属于任何队列的任务（定时任务）失败后不重试。
 */
public enum JobQueue {
    MAIL(Names.MAIL, 3, Duration.ofSeconds(1)),
    ACCOUNT_CLEANUP(Names.ACCOUNT_CLEANUP, 5, Duration.ofSeconds(10));

    /** 供 {@code @Job} 注解引用的队列名。 */
    public static final class Names {
        public static final String MAIL = "mail";
        public static final String ACCOUNT_CLEANUP = "account-cleanup";

        private Names() {}
    }

    private final String queueName;
    private final int attempts;
    private final Duration delay;

    JobQueue(String queueName, int attempts, Duration delay) {
        this.queueName = queueName;
        this.attempts = attempts;
        this.delay = delay;
    }

    public String queueName() {
        return queueName;
    }

    public int attempts() {
        return attempts;
    }

    /** 第 {@code failures} 次失败后的等待时间。 */
    public Duration backoff(int failures) {
        return delay.multipliedBy(1L << (failures - 1));
    }

    public static @Nullable JobQueue of(List<String> labels) {
        for (JobQueue queue : values()) {
            if (labels.contains(queue.queueName)) {
                return queue;
            }
        }
        return null;
    }
}
