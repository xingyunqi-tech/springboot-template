package tech.xingyunqi.template.infrastructure.accountcleanup;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 每小时第 25 分（Asia/Shanghai）：已注销超过 30 分钟仍未完成清理的用户（按 id 升序，最多 200 个）重新入队。 */
@Component
public class AccountCleanupCompensationTask {

    public static final String NAME = "account-cleanup-compensation";
    static final Duration GRACE = Duration.ofMinutes(30);
    static final int BATCH_SIZE = 200;

    private static final Logger LOG = LoggerFactory.getLogger(AccountCleanupCompensationTask.class);

    private final AccountCleanupMapper mapper;
    private final AccountCleanupProducer producer;
    private final Clock clock;

    public AccountCleanupCompensationTask(AccountCleanupMapper mapper, AccountCleanupProducer producer, Clock clock) {
        this.mapper = mapper;
        this.producer = producer;
        this.clock = clock;
    }

    @Job(name = NAME)
    public int run() {
        List<Integer> userIds =
                mapper.findPendingUserIds(OffsetDateTime.now(clock).minus(GRACE), BATCH_SIZE);
        userIds.forEach(producer::enqueueCleanup);
        LOG.info("[{}] {} account cleanups enqueued", NAME, userIds.size());
        return userIds.size();
    }
}
