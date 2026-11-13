package tech.xingyunqi.template.common.web.error;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** 错误信封 {@code {code, message, data}} 及需要附带的响应头；{@code message} 为字符串或字符串数组。 */
public record ErrorBody(
        int status, JsonNode message, @Nullable JsonNode data, Map<String, String> headers, String logError) {}
