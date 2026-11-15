package tech.xingyunqi.template.infrastructure.files.upload;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** busboy {@code parseDisposition}：类型小写；参数名小写、同名取第一个；普通值按 latin1，{@code name*} 按声明的字符集解码。 */
record Disposition(String type, Map<String, String> params) {

    private static final Pattern FORM_DATA_ESCAPES = Pattern.compile("(?i)%0A|%0D|%22");

    /** {@code filename*} 优先于 {@code filename}。 */
    @Nullable
    String fileName() {
        String extended = params.get("filename*");
        return extended != null ? extended : params.get("filename");
    }

    /** 格式不合法时返回 null（busboy 跳过该分段）。 */
    static @Nullable Disposition parse(String header) {
        if (header.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < header.length() && Tokens.isTokenChar(header.charAt(i))) {
            i++;
        }
        String type = header.substring(0, i).toLowerCase(Locale.ROOT);
        Map<String, String> params = new LinkedHashMap<>();
        if (i < header.length() && !parseParams(header, i, params)) {
            return null;
        }
        return new Disposition(type, params);
    }

    private static boolean parseParams(String text, int start, Map<String, String> params) {
        int i = start;
        int length = text.length();
        while (i < length) {
            i = skipWhitespace(text, i);
            if (i == length) {
                break;
            }
            if (text.charAt(i++) != ';') {
                return false;
            }
            i = skipWhitespace(text, i);
            if (i == length) {
                return false;
            }
            int nameStart = i;
            while (i < length && Tokens.isTokenChar(text.charAt(i))) {
                i++;
            }
            if (i == length || text.charAt(i) != '=') {
                return false;
            }
            String name = text.substring(nameStart, i).toLowerCase(Locale.ROOT);
            i++;
            if (i == length) {
                return false;
            }
            String value;
            if (name.endsWith("*")) {
                int valueStart = i;
                while (i < length && text.charAt(i) != ';' && text.charAt(i) != ' ' && text.charAt(i) != '\t') {
                    i++;
                }
                value = extended(text.substring(valueStart, i));
                if (value == null) {
                    return false;
                }
            } else if (text.charAt(i) == '"') {
                StringBuilder quoted = new StringBuilder();
                i++;
                boolean escaping = false;
                boolean closed = false;
                for (; i < length; i++) {
                    char c = text.charAt(i);
                    if (escaping) {
                        quoted.append(c);
                        escaping = false;
                    } else if (c == '\\') {
                        escaping = true;
                    } else if (c == '"') {
                        closed = true;
                        break;
                    } else if (!Tokens.isQuotedTextChar(c)) {
                        return false;
                    } else {
                        quoted.append(c);
                    }
                }
                if (!closed) {
                    return false;
                }
                i++;
                value = unescape(quoted.toString());
            } else {
                int valueStart = i;
                while (i < length && Tokens.isTokenChar(text.charAt(i))) {
                    i++;
                }
                if (i == valueStart) {
                    return false;
                }
                value = unescape(text.substring(valueStart, i));
            }
            params.putIfAbsent(name, value);
        }
        return true;
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** 浏览器对表单字段名与文件名中的换行与双引号做的转义。 */
    private static String unescape(String value) {
        return FORM_DATA_ESCAPES.matcher(value).replaceAll(match -> switch (match.group()
                .toUpperCase(Locale.ROOT)) {
            case "%0A" -> "\n";
            case "%0D" -> "\r";
            default -> "\"";
        });
    }

    /** RFC 5987 {@code charset'lang'pct-encoded}；字符集只认 utf-8 与 latin1。 */
    private static @Nullable String extended(String raw) {
        int first = raw.indexOf('\'');
        int second = first < 0 ? -1 : raw.indexOf('\'', first + 1);
        if (second < 0) {
            return null;
        }
        String charsetName = raw.substring(0, first).toLowerCase(Locale.ROOT);
        Charset charset = switch (charsetName) {
            case "utf-8", "utf8" -> StandardCharsets.UTF_8;
            case "latin1", "iso-8859-1", "ascii", "us-ascii" -> StandardCharsets.ISO_8859_1;
            default -> null;
        };
        if (charset == null) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String encoded = raw.substring(second + 1);
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write(high * 16 + low);
                    i += 2;
                    continue;
                }
            }
            bytes.write(c & 0xFF);
        }
        return new String(bytes.toByteArray(), charset);
    }
}
