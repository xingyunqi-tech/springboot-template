package tech.xingyunqi.template.modules.notifications.domain;

import java.time.Instant;

public record NotificationSetting(
        int id,
        int userId,
        String category,
        boolean enableInApp,
        boolean enableEmail,
        boolean enableSms,
        boolean enablePush,
        Instant createdAt,
        Instant updatedAt) {

    /** 各渠道开关；未知渠道为 false。 */
    public boolean allows(String channel) {
        return switch (channel) {
            case Notification.IN_APP -> enableInApp;
            case Notification.EMAIL -> enableEmail;
            case Notification.SMS -> enableSms;
            case Notification.PUSH -> enablePush;
            default -> false;
        };
    }
}
