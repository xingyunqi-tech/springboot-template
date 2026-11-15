package tech.xingyunqi.template.infrastructure.scheduler;

import java.time.ZoneId;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountCleanupCompensationTask;

/** 运行 background server 的进程启动后登记 recurring job（JobRunr 保证集群内单实例执行）；未运行 background server 的 api 进程启动时不访问任务表。 */
@Component
public class RecurringJobs {

    static final ZoneId UTC = ZoneId.of("UTC");
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JobScheduler scheduler;
    private final ObjectProvider<BackgroundJobServer> server;

    public RecurringJobs(JobScheduler scheduler, ObjectProvider<BackgroundJobServer> server) {
        this.scheduler = scheduler;
        this.server = server;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (server.getIfAvailable() == null) {
            return;
        }
        scheduler.<CleanupTasks>scheduleRecurrently(
                CleanupTasks.OLD_SESSIONS, "0 3 * * *", UTC, tasks -> tasks.cleanupOldSessions());
        scheduler.<CleanupTasks>scheduleRecurrently(
                CleanupTasks.READ_NOTIFICATIONS, "0 4 * * *", UTC, tasks -> tasks.cleanupReadNotifications());
        scheduler.<AccountCleanupCompensationTask>scheduleRecurrently(
                AccountCleanupCompensationTask.NAME, "25 * * * *", SHANGHAI, task -> task.run());
    }
}
