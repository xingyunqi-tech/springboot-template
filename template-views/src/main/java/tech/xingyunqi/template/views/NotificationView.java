package tech.xingyunqi.template.views;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** nestjs {@code Notification}：{@code metadata}、{@code readAt} 为空时不输出该键。 */
@JsonPropertyOrder({
    "id",
    "userId",
    "type",
    "category",
    "title",
    "content",
    "metadata",
    "channels",
    "sentChannels",
    "isRead",
    "readAt",
    "createdAt",
    "updatedAt",
    "deletedAt"
})
public record NotificationView(
        int id,
        int userId,
        String type,
        String category,
        String title,
        String content,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Map<String, @Nullable Object> metadata,
        List<String> channels,
        Map<String, @Nullable Object> sentChannels,
        boolean isRead,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Instant readAt,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt) {}
