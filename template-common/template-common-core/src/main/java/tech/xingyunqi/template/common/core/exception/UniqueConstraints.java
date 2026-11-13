package tech.xingyunqi.template.common.core.exception;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** nestjs {@code UNIQUE_CONSTRAINT_RESPONSES}：唯一约束名到错误响应的映射。 */
public final class UniqueConstraints {

    /** {@code errors} 与 {@code error} 均为翻译前的键。 */
    public record Response(
            int status,
            @Nullable Map<String, String> errors,
            @Nullable String error) {}

    private static final Response DEFAULT = new Response(409, null, null);

    private static final Map<String, Response> RESPONSES = Map.of(
            "UQ_e12875dfb3b1d92d7d7c5377e22", new Response(422, Map.of("email", "emailAlreadyExists"), null),
            "user_phone_key", new Response(422, Map.of("phone", "phoneAlreadyExists"), null),
            "user_wechatOpenId_key", new Response(422, Map.of("wechatOpenId", "wechatOpenIdAlreadyExists"), null),
            "user_alipayOpenId_key", new Response(422, Map.of("alipayOpenId", "alipayOpenIdAlreadyExists"), null),
            "REL_75e2be4ce11d447ef43be0e374", new Response(422, Map.of("photo", "alreadyExists"), null),
            "UQ_favorite_user_target", new Response(409, null, "alreadyFavorited"),
            "UQ_question_subjectId_contentHash", new Response(409, null, "duplicateQuestion"),
            "UQ_order_orderNo", new Response(500, null, null),
            "UQ_share_shareCode", new Response(500, null, null));

    private UniqueConstraints() {}

    public static Response lookup(@Nullable String constraint) {
        return constraint == null ? DEFAULT : RESPONSES.getOrDefault(constraint, DEFAULT);
    }
}
