package tech.xingyunqi.template.modules.notifications;

import org.springframework.stereotype.Component;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;

/** 删除该用户的全部通知与通知设置。 */
@Component
class NotificationsAccountCleaner implements AccountDataCleaner {

    static final int ORDER = 100;

    private final NotificationsService notifications;

    NotificationsAccountCleaner(NotificationsService notifications) {
        this.notifications = notifications;
    }

    @Override
    public String name() {
        return "notifications";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void clean(int userId) {
        notifications.removeByUser(userId);
    }
}
