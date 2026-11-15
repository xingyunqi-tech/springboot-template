package tech.xingyunqi.template.infrastructure.files.upload;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** {@code Content-Type}：小写的类型与参数（参数名小写，值可带引号）。 */
record MediaType(String type, Map<String, String> params) {

    static MediaType parse(String header) {
        String[] parts = header.split(";", -1);
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals < 0) {
                continue;
            }
            String name = parts[i].substring(0, equals).strip().toLowerCase(Locale.ROOT);
            String value = parts[i].substring(equals + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            params.putIfAbsent(name, value);
        }
        return new MediaType(parts[0].strip().toLowerCase(Locale.ROOT), params);
    }
}
