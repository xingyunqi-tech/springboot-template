package tech.xingyunqi.template.infrastructure.files.web;

import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.web.http.UriComponent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Express 5 默认查询解析（node {@code querystring.parse}）：只读 URL 中的查询串，重复键为数组。 */
final class QueryString {

    private static final int MAX_KEYS = 1000;

    private QueryString() {}

    static ObjectNode parse(@Nullable String raw) {
        ObjectNode query = JsonNodeFactory.instance.objectNode();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        int keys = 0;
        for (String pair : raw.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            if (keys++ == MAX_KEYS) {
                break;
            }
            int equals = pair.indexOf('=');
            String key = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            JsonNode existing = query.get(key);
            if (existing == null) {
                query.put(key, value);
            } else if (existing instanceof ArrayNode array) {
                array.add(value);
            } else {
                query.putArray(key).add(existing).add(value);
            }
        }
        return query;
    }

    private static String decode(String value) {
        String text = value.replace('+', ' ');
        try {
            return UriComponent.decode(text);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }
}
