package tech.xingyunqi.template.modules.notifications;

import org.jobrunr.jobs.lambdas.JobRequest;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.notifications.domain.WechatSubscribe;

/** {@code notification} 队列的任务：{@code name} 为 nestjs 任务名（亦为 JobRunr 任务名），其余字段按任务类型取用。 */
public record NotificationJob(
        String name,
        int notificationId,
        int userId,
        @Nullable String title,
        @Nullable String content,
        WechatSubscribe.@Nullable Payload payload)
        implements JobRequest {

    public static final String SEND_EMAIL = "send-email";
    public static final String SEND_SMS = "send-sms";
    public static final String SEND_WECHAT_SUBSCRIBE = "send-wechat-subscribe";

    public static NotificationJob email(int notificationId, int userId, String title, String content) {
        return new NotificationJob(SEND_EMAIL, notificationId, userId, title, content, null);
    }

    public static NotificationJob sms(int notificationId, int userId, String content) {
        return new NotificationJob(SEND_SMS, notificationId, userId, null, content, null);
    }

    public static NotificationJob wechatSubscribe(int notificationId, int userId, WechatSubscribe.Payload payload) {
        return new NotificationJob(SEND_WECHAT_SUBSCRIBE, notificationId, userId, null, null, payload);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public Class<NotificationJobHandler> getJobRequestHandler() {
        return NotificationJobHandler.class;
    }
}
