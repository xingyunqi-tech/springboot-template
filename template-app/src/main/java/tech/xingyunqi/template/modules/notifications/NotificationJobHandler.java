package tech.xingyunqi.template.modules.notifications;

import java.time.Duration;
import org.jobrunr.JobRunrException;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.mail.MailTransport;
import tech.xingyunqi.template.common.mail.OutgoingMail;
import tech.xingyunqi.template.core.users.UsersService;
import tech.xingyunqi.template.core.users.domain.User;
import tech.xingyunqi.template.infrastructure.queue.JobLimiter;
import tech.xingyunqi.template.infrastructure.queue.JobQueue;
import tech.xingyunqi.template.modules.notifications.domain.Notification;
import tech.xingyunqi.template.modules.notifications.domain.WechatSubscribe;

/** nestjs {@code NotificationQueueProcessor}：按任务名发送邮件、短信、微信订阅消息，并回写 {@code sentChannels}；三类任务共用进程内并发 5、每秒 10 个的限速。 */
@Component
public class NotificationJobHandler implements JobRequestHandler<NotificationJob> {

    static final int CONCURRENCY = 5;
    static final int RATE_LIMIT_MAX = 10;
    static final Duration RATE_LIMIT_DURATION = Duration.ofSeconds(1);

    private static final Logger LOG = LoggerFactory.getLogger(NotificationJobHandler.class);

    private final JobLimiter limiter = new JobLimiter(CONCURRENCY, RATE_LIMIT_MAX, RATE_LIMIT_DURATION);

    private final UsersService users;
    private final MailTransport mail;
    private final NotificationSender sender;
    private final NotificationsService notifications;

    public NotificationJobHandler(
            UsersService users, MailTransport mail, NotificationSender sender, NotificationsService notifications) {
        this.users = users;
        this.mail = mail;
        this.sender = sender;
        this.notifications = notifications;
    }

    @Override
    @Job(name = "%0", labels = JobQueue.Names.NOTIFICATION)
    public void run(NotificationJob job) throws InterruptedException {
        limiter.run(() -> process(job));
    }

    private void process(NotificationJob job) {
        LOG.info("Processing notification job of type {} for notification {}", job.name(), job.notificationId());
        try {
            switch (job.name()) {
                case NotificationJob.SEND_EMAIL -> sendEmail(job);
                case NotificationJob.SEND_SMS -> sendSms(job);
                case NotificationJob.SEND_WECHAT_SUBSCRIBE -> sendWechatSubscribe(job);
                default -> {}
            }
        } catch (RuntimeException e) {
            LOG.error(
                    "Notification job {} for notification {} failed: {}",
                    job.name(),
                    job.notificationId(),
                    e.toString());
            throw e;
        }
    }

    private void sendEmail(NotificationJob job) {
        String content = text(job.content());
        try {
            String email = users.findById(job.userId()).map(User::email).orElse(null);
            if (email == null || email.isEmpty()) {
                throw new IllegalStateException("User " + job.userId() + " not found or has no email");
            }
            mail.send(new OutgoingMail(email, text(job.title()), content, "<p>" + content + "</p>"));
        } catch (RuntimeException e) {
            record(job.notificationId(), Notification.EMAIL, false, message(e));
            throw e;
        }
        record(job.notificationId(), Notification.EMAIL, true, null);
        LOG.info("Email sent for notification {}", job.notificationId());
    }

    private void sendSms(NotificationJob job) {
        try {
            String phone = users.findById(job.userId()).map(User::phone).orElse(null);
            if (phone == null || phone.isEmpty()) {
                throw new IllegalStateException("User " + job.userId() + " not found or has no phone");
            }
            NotificationSender.SmsResult result = sender.sendSms(phone, text(job.content()));
            if (!result.success()) {
                throw new IllegalStateException(result.message() != null ? result.message() : "SMS send failed");
            }
        } catch (RuntimeException e) {
            record(job.notificationId(), Notification.SMS, false, message(e));
            throw e;
        }
        record(job.notificationId(), Notification.SMS, true, null);
        LOG.info("SMS sent for notification {}", job.notificationId());
    }

    private void sendWechatSubscribe(NotificationJob job) {
        WechatSubscribe.Payload payload = job.payload();
        if (payload == null) {
            return;
        }
        WechatSubscribe.Message message = WechatSubscribe.build(payload, sender.subscribeTemplate(payload.scene()));
        if (message == null) {
            LOG.debug("Subscribe template for scene {} not configured, skipped", payload.scene());
            return;
        }
        String openId = users.findById(job.userId()).map(User::wechatOpenId).orElse(null);
        if (openId == null || openId.isEmpty()) {
            record(job.notificationId(), Notification.PUSH, false, "User has no WeChat openId");
            return;
        }
        try {
            sender.sendSubscribeMessage(openId, message);
        } catch (RuntimeException e) {
            String error = message(e);
            record(job.notificationId(), Notification.PUSH, false, error);
            if (e instanceof NotificationSender.SubscribeFailure failure && failure.unrecoverable()) {
                throw new JobRunrException(error, true, e);
            }
            throw e;
        }
        record(job.notificationId(), Notification.PUSH, true, null);
        LOG.info("WeChat subscribe message sent for notification {}", job.notificationId());
    }

    /** 记录渠道发送结果，记录失败只写日志不抛出。 */
    private void record(int notificationId, String channel, boolean sent, @Nullable String error) {
        try {
            notifications.recordChannelResult(notificationId, channel, sent, error);
        } catch (RuntimeException e) {
            LOG.warn("Failed to record {} result for notification {}: {}", channel, notificationId, e.toString());
        }
    }

    private static String message(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : "Unknown error";
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }
}
