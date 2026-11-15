package tech.xingyunqi.template.infrastructure.accountcleanup;

import org.jobrunr.jobs.lambdas.JobRequest;

/** {@code account-cleanup} 队列的 {@code clean-account} 任务参数。 */
public record AccountCleanupJob(int userId) implements JobRequest {

    @Override
    public Class<AccountCleanupJobHandler> getJobRequestHandler() {
        return AccountCleanupJobHandler.class;
    }
}
