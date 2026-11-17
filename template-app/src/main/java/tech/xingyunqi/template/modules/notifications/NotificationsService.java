package tech.xingyunqi.template.modules.notifications;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.json.JsCompatibleModule;
import tech.xingyunqi.template.common.mybatis.AfterCommit;
import tech.xingyunqi.template.common.mybatis.WriteTransaction;
import tech.xingyunqi.template.modules.notifications.domain.CreateNotification;
import tech.xingyunqi.template.modules.notifications.domain.Notification;
import tech.xingyunqi.template.modules.notifications.domain.NotificationSetting;
import tech.xingyunqi.template.modules.notifications.domain.SettingFlags;
import tech.xingyunqi.template.modules.notifications.domain.WechatSubscribe;

/** 站内通知与通知设置（nestjs NotificationsService）：按用户设置过滤渠道，提交后投递站外渠道任务。 */
@Service
public class NotificationsService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationsService.class);

    public record Page(List<Notification> data, long total) {}

    private final NotificationRepository notifications;
    private final NotificationSettingRepository settings;
    private final JobRequestScheduler jobs;
    private final Clock clock;

    public NotificationsService(
            NotificationRepository notifications,
            NotificationSettingRepository settings,
            JobRequestScheduler jobs,
            Clock clock) {
        this.notifications = notifications;
        this.settings = settings;
        this.jobs = jobs;
        this.clock = clock;
    }

    @WriteTransaction
    public Notification create(CreateNotification data) {
        LOG.info("Creating notification for user: {}", data.userId());
        Optional<NotificationSetting> setting = settings.find(data.userId(), data.category());
        List<String> channels = setting.map(
                        s -> data.channels().stream().filter(s::allows).toList())
                .orElse(data.channels());
        Notification notification = notifications.create(data, channels);
        AfterCommit.run(() -> queueExternal(notification));
        LOG.info("Notification created successfully: {}", notification.id());
        return notification;
    }

    /** 按通知的渠道入队：EMAIL、SMS，以及 metadata 带 wechatSubscribe 的 PUSH。 */
    void queueExternal(Notification notification) {
        for (String channel : notification.channels()) {
            NotificationJob job = switch (channel) {
                case Notification.EMAIL ->
                    NotificationJob.email(
                            notification.id(), notification.userId(), notification.title(), notification.content());
                case Notification.SMS ->
                    NotificationJob.sms(notification.id(), notification.userId(), notification.content());
                case Notification.PUSH -> {
                    WechatSubscribe.Payload payload = wechatSubscribe(notification.metadata());
                    yield payload == null
                            ? null
                            : NotificationJob.wechatSubscribe(notification.id(), notification.userId(), payload);
                }
                default -> null;
            };
            if (job != null) {
                jobs.enqueue(job);
            }
        }
    }

    @WriteTransaction
    public void recordChannelResult(int id, String channel, boolean sent, @Nullable String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", sent);
        if (error != null && !error.isEmpty()) {
            result.put("error", error);
        }
        result.put("sentAt", JsCompatibleModule.isoTimestamp(clock.instant()));
        notifications.recordChannelResult(id, channel, result);
    }

    public Page findPage(int userId, NotificationRepository.Filter filter, long page, long limit) {
        List<Notification> data = notifications.findMany(userId, filter, (page - 1) * limit, limit);
        return new Page(data, notifications.count(userId, filter));
    }

    public Notification findOne(int id, int userId) {
        return notifications.findByIdAndUser(id, userId).orElseThrow(NotificationsService::notFound);
    }

    @WriteTransaction
    public Notification markAsRead(int id, int userId) {
        if (notifications.findByIdAndUser(id, userId).isEmpty()) {
            throw notFound();
        }
        Notification notification = notifications.markAsRead(id, now());
        LOG.info("Notification {} marked as read by user {}", id, userId);
        return notification;
    }

    @WriteTransaction
    public void markAllAsRead(int userId) {
        notifications.markAllAsRead(userId, now());
        LOG.info("All notifications marked as read for user {}", userId);
    }

    public long getUnreadCount(int userId) {
        return notifications.count(userId, new NotificationRepository.Filter(null, false));
    }

    @WriteTransaction
    public void remove(int id, int userId) {
        if (notifications.findByIdAndUser(id, userId).isEmpty()) {
            throw notFound();
        }
        notifications.softDelete(id);
        LOG.info("Notification {} deleted by user {}", id, userId);
    }

    public List<NotificationSetting> getSettings(int userId) {
        return settings.findAll(userId);
    }

    /** 不存在时创建；取值未变化时不写入（updatedAt 不变）。 */
    @WriteTransaction
    public NotificationSetting updateSettings(int userId, String category, SettingFlags flags) {
        Optional<NotificationSetting> existing = settings.find(userId, category);
        NotificationSetting setting;
        if (existing.isEmpty()) {
            setting = settings.create(userId, category, flags);
        } else if (flags.sameAs(existing.get())) {
            setting = existing.get();
        } else {
            setting = settings.update(existing.get().id(), flags);
        }
        LOG.info("Notification settings updated for user {}, category {}", userId, category);
        return setting;
    }

    @WriteTransaction
    public void initializeDefaultSettings(int userId) {
        settings.initializeDefaults(userId, Notification.CATEGORIES);
        LOG.info("Default notification settings initialized for user {}", userId);
    }

    @WriteTransaction
    public void removeByUser(int userId) {
        notifications.removeByUser(userId);
        settings.removeByUser(userId);
    }

    /** JS {@code new Date()}：毫秒精度。 */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static WechatSubscribe.@Nullable Payload wechatSubscribe(@Nullable Map<String, @Nullable Object> metadata) {
        Object value = metadata == null ? null : metadata.get("wechatSubscribe");
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (map.get("values") instanceof Map<?, ?> raw) {
            raw.forEach((key, item) -> {
                if (item != null) {
                    values.put(String.valueOf(key), String.valueOf(item));
                }
            });
        }
        Object page = map.get("page");
        return new WechatSubscribe.Payload(
                String.valueOf(map.get("scene")), values, page == null ? null : String.valueOf(page));
    }

    private static HttpStatusException notFound() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "notificationNotFound");
        body.put("message", "Notification not found");
        return HttpStatusException.notFound(body);
    }
}
