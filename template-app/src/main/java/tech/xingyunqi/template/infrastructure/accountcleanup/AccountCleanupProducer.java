package tech.xingyunqi.template.infrastructure.accountcleanup;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.storage.JobNotFoundException;
import org.jobrunr.storage.StorageProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.core.alert.AlertReporter;
import tech.xingyunqi.template.common.mybatis.AfterCommit;
import tech.xingyunqi.template.common.redis.LockService;

/**
 * 账号清理任务入队（nestjs jobId {@code user-<id>}）：job id 为 {@code user-<id>} 的名字型 UUID；同一用户的任务排队或执行中时跳过， 已结束、或未结束但超过
 * {@value #PENDING_RETENTION_HOURS} 小时未更新时先删除再入队。投递时持
 * {@code lock:account-cleanup:enqueue:user-<id>}，并发投递同一用户时只有一方入队。入队失败时告警。
 */
@Component
public class AccountCleanupProducer {

    static final int PENDING_RETENTION_HOURS = 24;
    static final Duration ENQUEUE_LOCK_TTL = Duration.ofSeconds(30);
    static final Set<StateName> FINISHED = EnumSet.of(StateName.SUCCEEDED, StateName.FAILED, StateName.DELETED);

    private static final Logger LOG = LoggerFactory.getLogger(AccountCleanupProducer.class);

    private final JobRequestScheduler jobs;
    private final StorageProvider storage;
    private final LockService locks;
    private final AlertReporter alerts;
    private final Clock clock;

    public AccountCleanupProducer(
            JobRequestScheduler jobs, StorageProvider storage, LockService locks, AlertReporter alerts, Clock clock) {
        this.jobs = jobs;
        this.storage = storage;
        this.locks = locks;
        this.alerts = alerts;
        this.clock = clock;
    }

    public static UUID jobId(int userId) {
        return UUID.nameUUIDFromBytes(("user-" + userId).getBytes(StandardCharsets.UTF_8));
    }

    /** 当前事务提交后入队；4xx 提交路径上不入队。 */
    public void enqueueAfterCommit(int userId) {
        AfterCommit.run(() -> enqueueCleanup(userId));
    }

    public void enqueueCleanup(int userId) {
        try {
            add(userId);
        } catch (RuntimeException e) {
            AccountCleanupAlerts.raise(alerts, userId, e, null);
        }
    }

    private void add(int userId) {
        String lockKey = "account-cleanup:enqueue:user-" + userId;
        String token = locks.tryAcquire(lockKey, ENQUEUE_LOCK_TTL.toMillis());
        if (token == null) {
            LOG.info("Account cleanup for user {} is being enqueued elsewhere, skipped", userId);
            return;
        }
        try {
            UUID id = jobId(userId);
            Job existing = find(id);
            if (existing != null) {
                if (!FINISHED.contains(existing.getState()) && !stale(existing)) {
                    LOG.info("Account cleanup for user {} is already pending", userId);
                    return;
                }
                storage.deletePermanently(id);
            }
            jobs.enqueue(id, new AccountCleanupJob(userId));
        } finally {
            locks.release(lockKey, token);
        }
    }

    private boolean stale(Job job) {
        return job.getUpdatedAt().isBefore(clock.instant().minus(Duration.ofHours(PENDING_RETENTION_HOURS)));
    }

    private @Nullable Job find(UUID id) {
        try {
            return storage.getJobById(id);
        } catch (JobNotFoundException e) {
            return null;
        }
    }
}
