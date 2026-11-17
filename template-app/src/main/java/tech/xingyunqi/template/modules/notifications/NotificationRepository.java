package tech.xingyunqi.template.modules.notifications;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.notifications.domain.CreateNotification;
import tech.xingyunqi.template.modules.notifications.domain.Notification;

/** 用户可见的通知：未删除且投递渠道包含站内信。 */
public interface NotificationRepository {

    /** 列表筛选：为 null 的条件不参与查询。 */
    record Filter(@Nullable String category, @Nullable Boolean isRead) {}

    /** 以 {@code channels} 为最终渠道写入。 */
    Notification create(CreateNotification data, List<String> channels);

    /** 按创建时间倒序。 */
    List<Notification> findMany(int userId, Filter filter, long offset, long limit);

    long count(int userId, Filter filter);

    Optional<Notification> findByIdAndUser(int id, int userId);

    Notification markAsRead(int id, Instant readAt);

    /** 含已删除的通知。 */
    void markAllAsRead(int userId, Instant readAt);

    void softDelete(int id);

    /** 合并写入 {@code sentChannels[channel]}。 */
    void recordChannelResult(int id, String channel, Map<String, Object> result);

    void removeByUser(int userId);
}
