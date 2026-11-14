package tech.xingyunqi.template.core.users.web;

import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.core.users.domain.Patch;
import tech.xingyunqi.template.core.users.domain.UserInput;
import tools.jackson.databind.JsonNode;

/** 由 ValidationPipe 转换后的请求体构造 {@link UserInput}：未提供的字段为 absent，显式 null 为 null。 */
public final class UserInputs {

    private UserInputs() {}

    public static UserInput from(@Nullable JsonNode body) {
        if (body == null || !body.isObject()) {
            return UserInput.empty();
        }
        return new UserInput(
                text(body, "email"),
                text(body, "password"),
                text(body, "provider"),
                text(body, "firstName"),
                text(body, "lastName"),
                text(body, "phone"),
                text(body, "nickname"),
                scalar(body, "gender"),
                text(body, "birthday"),
                ref(body, "photo"),
                ref(body, "role"),
                ref(body, "status"),
                text(body, "wechatOpenId"),
                text(body, "wechatUnionId"),
                text(body, "alipayOpenId"));
    }

    public static @Nullable String string(@Nullable JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value == null || value.isNull() ? null : value.isString() ? value.asString() : value.toString();
    }

    private static Patch<String> text(JsonNode body, String field) {
        if (!body.has(field)) {
            return Patch.absent();
        }
        JsonNode value = body.get(field);
        return Patch.of(value.isNull() ? null : value.isString() ? value.asString() : value.toString());
    }

    private static Patch<Object> scalar(JsonNode body, String field) {
        if (!body.has(field)) {
            return Patch.absent();
        }
        return Patch.of(value(body.get(field)));
    }

    private static Patch<UserInput.Ref> ref(JsonNode body, String field) {
        if (!body.has(field)) {
            return Patch.absent();
        }
        JsonNode value = body.get(field);
        if (value.isNull()) {
            return Patch.of(null);
        }
        return Patch.of(new UserInput.Ref(value(value.get("id"))));
    }

    private static @Nullable Object value(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.isString() ? node.asString() : node.toString();
    }
}
