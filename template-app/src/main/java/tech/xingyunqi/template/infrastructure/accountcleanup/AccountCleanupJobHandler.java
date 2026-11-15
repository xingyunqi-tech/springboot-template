package tech.xingyunqi.template.infrastructure.accountcleanup;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.jobrunr.server.runner.ThreadLocalJobContext;
import org.jobrunr.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.redis.LockService;
import tech.xingyunqi.template.infrastructure.queue.JobQueue;
import tech.xingyunqi.template.infrastructure.queue.JobYieldException;
import tech.xingyunqi.template.infrastructure.queue.QueueJobFilter;

/**
 * 进程内串行执行（BullMQ 并发 1）：已有清理在执行时抛 {@link JobYieldException} 让出 worker；执行时持
 * {@code lock:account-cleanup:user-<id>}，锁被占用时跳过；失败由队列重试并重新执行全部清理器，最后一次仍失败时告警。
 */
@Component
public class AccountCleanupJobHandler implements JobRequestHandler<AccountCleanupJob> {

    static final Duration RUN_LOCK_TTL = Duration.ofHours(1);

    private static final Logger LOG = LoggerFactory.getLogger(AccountCleanupJobHandler.class);

    private final AccountCleanupRunner runner;
    private final LockService locks;
    private final StorageProvider storage;
    private final ReentrantLock serial = new ReentrantLock();

    public AccountCleanupJobHandler(AccountCleanupRunner runner, LockService locks, StorageProvider storage) {
        this.runner = runner;
        this.locks = locks;
        this.storage = storage;
    }

    static String runLockKey(int userId) {
        return JobQueue.Names.ACCOUNT_CLEANUP + ":user-" + userId;
    }

    @Override
    @Job(name = "clean-account", labels = JobQueue.Names.ACCOUNT_CLEANUP)
    public void run(AccountCleanupJob job) {
        int userId = job.userId();
        if (!serial.tryLock()) {
            throw new JobYieldException("Another account cleanup is running in this process");
        }
        try {
            String token = locks.tryAcquire(runLockKey(userId), RUN_LOCK_TTL.toMillis());
            if (token == null) {
                LOG.info("Account cleanup for user {} is running elsewhere, skipped", userId);
                return;
            }
            try {
                runner.run(userId);
            } catch (RuntimeException e) {
                if (lastAttempt()) {
                    runner.reportFailure(userId, e);
                }
                throw e;
            } finally {
                locks.release(runLockKey(userId), token);
            }
        } finally {
            serial.unlock();
        }
    }

    private boolean lastAttempt() {
        JobContext context = ThreadLocalJobContext.getJobContext();
        int failures = context == null ? 0 : QueueJobFilter.failures(storage.getJobById(context.getJobId()));
        return failures + 1 >= JobQueue.ACCOUNT_CLEANUP.attempts();
    }
}
