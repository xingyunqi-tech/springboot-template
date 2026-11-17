package tech.xingyunqi.template.modules.feedbacks.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record Feedback(
        int id,
        @Nullable Integer userId,
        String type,
        String content,
        @Nullable List<String> images,
        @Nullable String contact,
        String status,
        @Nullable String reply,
        @Nullable String questionId,
        @Nullable Integer questionVersion,
        @Nullable String reason,
        @Nullable String errataId,
        @Nullable String messageId,
        @Nullable Map<String, @Nullable Object> messageSnapshot,
        Instant createdAt,
        Instant updatedAt) {

    public static final String PENDING = "pending";

    /** 通用反馈接口可提交的类型。 */
    public static final List<String> GENERAL_TYPES = List.of("bug", "suggestion", "complaint", "other");

    /** 用户可通过反馈接口查询的类型。 */
    public static final List<String> USER_QUERYABLE_TYPES =
            List.of("bug", "suggestion", "complaint", "other", "question_error");
}
