package tech.xingyunqi.template.modules.notifications.domain;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** nestjs {@code CreateNotificationDto}。 */
public record CreateNotification(
        int userId,
        String type,
        String category,
        String title,
        String content,
        @Nullable Map<String, @Nullable Object> metadata,
        List<String> channels) {}
