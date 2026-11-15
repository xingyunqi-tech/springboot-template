package tech.xingyunqi.template.infrastructure.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.states.EnqueuedState;
import org.jobrunr.jobs.states.FailedState;
import org.jobrunr.jobs.states.JobState;
import org.jobrunr.jobs.states.ProcessingState;
import org.jobrunr.jobs.states.ScheduledState;
import org.jobrunr.jobs.states.StateName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.integrations.mail.MailJob;

class QueueJobFilterTest {

    final AppMetrics metrics = new AppMetrics("app");
    final QueueJobFilter filter = new QueueJobFilter(metrics);

    /** 已执行 {@code failures} 次且最后一次失败的任务。 */
    static Job failedJob(List<String> labels, int failures) {
        List<JobState> history = new ArrayList<>();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= failures; i++) {
            history.add(new EnqueuedState(at));
            history.add(new ProcessingState(UUID.randomUUID(), "worker", at));
            history.add(new FailedState(
                    "An exception occurred during the performance of the job",
                    RuntimeException.class.getName(),
                    "boom",
                    null,
                    null,
                    "",
                    false,
                    at.plusSeconds(i)));
        }
        Job job = new Job(
                UUID.randomUUID(),
                1,
                new JobDetails(new MailJob("activation", "a@b.c", Map.of(), null)),
                history,
                new ConcurrentHashMap<>());
        job.setLabels(labels);
        return job;
    }

    static FailedState yieldState(Instant at) {
        return new FailedState(
                "An exception occurred during the performance of the job",
                JobYieldException.class.getName(),
                "busy",
                null,
                null,
                "",
                false,
                at);
    }

    List<Duration> backoffs(String queue, int attempts) {
        List<Duration> delays = new ArrayList<>();
        for (int failures = 1; failures <= attempts; failures++) {
            Job job = failedJob(List.of(queue), failures);
            FailedState failed = job.getJobState();
            filter.onStateElection(job, failed);
            if (job.getState() == StateName.SCHEDULED) {
                ScheduledState scheduled = job.getJobState();
                delays.add(Duration.between(failed.getCreatedAt(), scheduled.getScheduledAt()));
            } else {
                assertThat(failures).isEqualTo(attempts);
                assertThat(job.getState()).isEqualTo(StateName.FAILED);
            }
        }
        return delays;
    }

    @Test
    @DisplayName("mail 共 3 次尝试，失败后等待 1s、2s")
    void mailRetriesTwiceWithExponentialBackoff() {
        assertThat(backoffs(JobQueue.Names.MAIL, 3)).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("account-cleanup 共 5 次尝试，失败后等待 10s、20s、40s、80s")
    void accountCleanupRetriesFourTimes() {
        assertThat(backoffs(JobQueue.Names.ACCOUNT_CLEANUP, 5))
                .containsExactly(
                        Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(40), Duration.ofSeconds(80));
    }

    /** 首次执行失败，之后让出 {@code yields} 次，最后一次执行以 {@code last} 结束的 account-cleanup 任务。 */
    static Job yieldedJob(int yields, FailedState last) {
        Job job = failedJob(List.of(JobQueue.Names.ACCOUNT_CLEANUP), 1);
        List<JobState> history = new ArrayList<>(job.getJobStates());
        Instant at = Instant.parse("2026-01-02T00:00:00Z");
        for (int i = 0; i < yields; i++) {
            history.add(new EnqueuedState(at));
            history.add(new ProcessingState(UUID.randomUUID(), "worker", at));
            history.add(yieldState(at));
            history.add(new ScheduledState(at.plus(QueueJobFilter.YIELD_DELAY), "Yielded"));
        }
        history.add(new EnqueuedState(at));
        history.add(new ProcessingState(UUID.randomUUID(), "worker", at));
        history.add(last);
        Job result = new Job(UUID.randomUUID(), 1, job.getJobDetails(), history, new ConcurrentHashMap<>());
        result.setLabels(job.getLabels());
        return result;
    }

    @Test
    @DisplayName("抛出 JobYieldException 的任务 5s 后重跑，让出不计入失败次数")
    void yieldReschedulesWithoutCountingFailure() {
        FailedState yielded = yieldState(Instant.parse("2026-01-03T00:00:00Z"));
        Job job = yieldedJob(6, yielded);

        filter.onStateElection(job, yielded);

        ScheduledState scheduled = job.getJobState();
        assertThat(scheduled.getScheduledAt()).isEqualTo(yielded.getCreatedAt().plus(Duration.ofSeconds(5)));
        assertThat(QueueJobFilter.failures(job)).isEqualTo(1);
    }

    @Test
    @DisplayName("让出之后的失败按实际失败次数退避与计算剩余尝试")
    void failureAfterYieldsUsesActualFailureCount() {
        Job job = yieldedJob(6, failedJob(List.of(), 1).getJobState());
        FailedState failed = job.getJobState();

        filter.onStateElection(job, failed);

        ScheduledState scheduled = job.getJobState();
        assertThat(Duration.between(failed.getCreatedAt(), scheduled.getScheduledAt()))
                .isEqualTo(Duration.ofSeconds(20));
        assertThat(scheduled.getReason()).isEqualTo("Retry 2 of 4");
    }

    @Test
    @DisplayName("不属于队列的任务（定时任务）失败后不重试")
    void jobsOutsideQueuesAreNotRetried() {
        Job job = failedJob(List.of(), 1);
        filter.onStateElection(job, job.getJobState());
        assertThat(job.getState()).isEqualTo(StateName.FAILED);
    }

    @Test
    @DisplayName("任务成功或最终失败时按队列名计 queue_jobs_total")
    void recordsQueueJobs() {
        filter.onProcessingSucceeded(failedJob(List.of(JobQueue.Names.MAIL), 1));
        filter.onFailedAfterRetries(failedJob(List.of(JobQueue.Names.ACCOUNT_CLEANUP), 5));
        filter.onProcessingSucceeded(failedJob(List.of(), 1));
        assertThat(metrics.value("queue_jobs_total", "mail", "completed")).isEqualTo(1);
        assertThat(metrics.value("queue_jobs_total", "account-cleanup", "failed"))
                .isEqualTo(1);
    }
}
