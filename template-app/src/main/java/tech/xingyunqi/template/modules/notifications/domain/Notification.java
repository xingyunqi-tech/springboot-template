package tech.xingyunqi.template.modules.notifications.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record Notification(
        int id,
        int userId,
        String type,
        String category,
        String title,
        String content,
        @Nullable Map<String, @Nullable Object> metadata,
        List<String> channels,
        Map<String, @Nullable Object> sentChannels,
        boolean isRead,
        @Nullable Instant readAt,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt) {

    public static final String IN_APP = "IN_APP";
    public static final String EMAIL = "EMAIL";
    public static final String SMS = "SMS";
    public static final String PUSH = "PUSH";

    public static final List<String> CATEGORIES =
            List.of("ORDER", "PAYMENT", "SYSTEM", "PROMOTION", "ACTIVITY", "STUDY");
}
