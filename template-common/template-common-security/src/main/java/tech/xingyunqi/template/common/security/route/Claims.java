package tech.xingyunqi.template.common.security.route;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** 令牌载荷取值，按 JS 真值与数字转换语义。 */
public final class Claims {

    private Claims() {}

    /** 载荷中的正整数 id（数字或数字字符串）；缺失、假值或非整数时为 null。 */
    public static @Nullable Integer positiveInt(@Nullable JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToInt() && value.asInt() != 0) {
            return value.asInt();
        }
        if (value.isString() && value.asString().matches("[1-9]\\d{0,9}")) {
            try {
                return Integer.valueOf(value.asString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static String jsString(@Nullable JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return "undefined";
        }
        if (value.isNull()) {
            return "null";
        }
        return value.isString() ? value.asString() : value.toString();
    }
}
