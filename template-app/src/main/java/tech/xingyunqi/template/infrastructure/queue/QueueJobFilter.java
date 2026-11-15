package tech.xingyunqi.template.infrastructure.queue;

import java.time.Duration;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.filters.ElectStateFilter;
import org.jobrunr.jobs.filters.JobServerFilter;
import org.jobrunr.jobs.states.FailedState;
import org.jobrunr.jobs.states.JobState;
import org.jobrunr.scheduling.exceptions.JobNotFoundException;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;

/**
 * 替代 JobRunr 默认的 {@code RetryFilter}：按 {@link JobQueue} 的尝试次数与退避安排重试，抛出 {@link JobYieldException} 的任务在
 * {@link #YIELD_DELAY} 后重跑且不计入失败次数，并在任务结束时计 {@code queue_jobs_total}。
 */
public final class QueueJobFilter implements ElectStateFilter, JobServerFilter {

    static final String COMPLETED = "completed";
    static final String FAILED = "failed";
    static final Duration YIELD_DELAY = Duration.ofSeconds(5);

    private final AppMetrics metrics;

    public QueueJobFilter(AppMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onStateElection(Job job, JobState newState) {
        if (!(newState instanceof FailedState failed)) {
            return;
        }
        if (yielded(failed)) {
            job.scheduleAt(failed.getCreatedAt().plus(YIELD_DELAY), "Yielded");
            return;
        }
        if (failed.mustNotRetry() || failed.getException() instanceof JobNotFoundException) {
            return;
        }
        JobQueue queue = JobQueue.of(job.getLabels());
        if (queue == null) {
            return;
        }
        int failures = failures(job);
        if (failures >= queue.attempts()) {
            return;
        }
        job.scheduleAt(
                failed.getCreatedAt().plus(queue.backoff(failures)),
                String.format("Retry %d of %d", failures, queue.attempts() - 1));
    }

    /** 失败次数，不含让出。 */
    public static int failures(Job job) {
        return (int) job.getJobStatesOfType(FailedState.class)
                .filter(failed -> !yielded(failed))
                .count();
    }

    private static boolean yielded(FailedState failed) {
        return JobYieldException.class.getName().equals(failed.getExceptionType());
    }

    @Override
    public void onProcessingSucceeded(Job job) {
        record(job, COMPLETED);
    }

    @Override
    public void onFailedAfterRetries(Job job) {
        record(job, FAILED);
    }

    private void record(Job job, String status) {
        JobQueue queue = JobQueue.of(job.getLabels());
        if (queue != null) {
            metrics.queueJob(queue.queueName(), status);
        }
    }
}
