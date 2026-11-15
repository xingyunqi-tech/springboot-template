package tech.xingyunqi.template.infrastructure.scheduler;

import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** nestjs 的两个定时清理任务（容器时区 UTC）；条件更新保证重复执行无副作用。 */
@Component
public class CleanupTasks {

    public static final String OLD_SESSIONS = "cleanup-old-sessions";
    public static final String READ_NOTIFICATIONS = "cleanup-read-notifications";

    private static final Logger LOG = LoggerFactory.getLogger(CleanupTasks.class);

    private final CleanupMapper mapper;

    public CleanupTasks(CleanupMapper mapper) {
        this.mapper = mapper;
    }

    /** 每天 03:00 UTC 物理删除软删除超过 7 天的会话。 */
    @Job(name = OLD_SESSIONS)
    public int cleanupOldSessions() {
        int count = mapper.deleteOldSessions();
        LOG.info("[{}] Cleanup completed: {} old sessions permanently deleted", OLD_SESSIONS, count);
        return count;
    }

    /** 每天 04:00 UTC 软删除已读超过 30 天的通知。 */
    @Job(name = READ_NOTIFICATIONS)
    public int cleanupReadNotifications() {
        int count = mapper.softDeleteReadNotifications();
        LOG.info("[{}] Cleanup completed: {} old read notifications soft deleted", READ_NOTIFICATIONS, count);
        return count;
    }
}
