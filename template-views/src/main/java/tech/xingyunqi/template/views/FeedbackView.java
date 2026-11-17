package tech.xingyunqi.template.views;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** nestjs {@code Feedback}：{@code images} 为空时不输出该键。 */
@JsonPropertyOrder({
    "id",
    "userId",
    "type",
    "content",
    "images",
    "contact",
    "status",
    "reply",
    "questionId",
    "questionVersion",
    "reason",
    "errataId",
    "messageId",
    "messageSnapshot",
    "createdAt",
    "updatedAt"
})
public record FeedbackView(
        int id,
        @Nullable Integer userId,
        String type,
        String content,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> images,
        @Nullable String contact,
        String status,
        @Nullable String reply,
        @Nullable String questionId,
        @Nullable Integer questionVersion,
        @Nullable String reason,
        @Nullable String errataId,
        @Nullable String messageId,
        @Nullable Object messageSnapshot,
        Instant createdAt,
        Instant updatedAt) {}
