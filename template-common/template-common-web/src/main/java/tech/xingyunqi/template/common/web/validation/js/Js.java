package tech.xingyunqi.template.common.web.validation.js;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.json.JsNumbers;
import tech.xingyunqi.template.common.json.JsStrings;

/** JavaScript 抽象操作：类型转换、相等、字符串化与字面量替换。 */
public final class Js {

    public static final String WHITESPACE = JsStrings.WHITESPACE;

    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final Pattern RADIX = Pattern.compile("0([xXoObB])([0-9a-fA-F]+)");

    private Js() {}

    public static boolean isWhitespace(char c) {
        return JsStrings.isWhitespace(c);
    }

    public static String trim(String value) {
        return JsStrings.trim(value);
    }

    public static boolean isNullish(JsValue value) {
        return value == JsValue.UNDEFINED || value == JsValue.NULL;
    }

    public static boolean isPrimitive(JsValue value) {
        return value instanceof JsValue.Str || value instanceof JsValue.Bool || value instanceof JsValue.Num;
    }

    public static boolean truthy(JsValue value) {
        return switch (value) {
            case JsValue.Undefined u -> false;
            case JsValue.Null n -> false;
            case JsValue.Bool b -> b.value();
            case JsValue.Num n -> n.value() != 0 && !Double.isNaN(n.value());
            case JsValue.Str s -> !s.value().isEmpty();
            default -> true;
        };
    }

    public static double toNumber(JsValue value) {
        return switch (value) {
            case JsValue.Undefined u -> Double.NaN;
            case JsValue.Null n -> 0;
            case JsValue.Bool b -> b.value() ? 1 : 0;
            case JsValue.Num n -> n.value();
            case JsValue.Str s -> stringToNumber(s.value());
            case JsValue.Array a -> stringToNumber(toStr(a));
            default -> Double.NaN;
        };
    }

    public static double stringToNumber(String raw) {
        String text = trim(raw);
        if (text.isEmpty()) {
            return 0;
        }
        if (text.equals("Infinity") || text.equals("+Infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (text.equals("-Infinity")) {
            return Double.NEGATIVE_INFINITY;
        }
        Matcher radix = RADIX.matcher(text);
        if (radix.matches()) {
            int base = switch (Character.toLowerCase(radix.group(1).charAt(0))) {
                case 'x' -> 16;
                case 'o' -> 8;
                default -> 2;
            };
            try {
                return new BigInteger(radix.group(2), base).doubleValue();
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        if (DECIMAL.matcher(text).matches()) {
            return Double.parseDouble(text);
        }
        return Double.NaN;
    }

    /** {@code String(value)}；对象一律为 {@code [object Object]}。 */
    public static String toStr(JsValue value) {
        return switch (value) {
            case JsValue.Undefined u -> "undefined";
            case JsValue.Null n -> "null";
            case JsValue.Bool b -> b.value() ? "true" : "false";
            case JsValue.Num n -> JsNumbers.toString(n.value());
            case JsValue.Str s -> s.value();
            case JsValue.Array a -> {
                List<String> parts = new ArrayList<>();
                for (JsValue item : a.items()) {
                    parts.add(isNullish(item) ? "" : toStr(item));
                }
                yield String.join(",", parts);
            }
            default -> "[object Object]";
        };
    }

    @SuppressWarnings("ReferenceEquality")
    public static boolean strictEquals(JsValue left, JsValue right) {
        if (left instanceof JsValue.Num l && right instanceof JsValue.Num r) {
            return l.value() == r.value();
        }
        if (left instanceof JsValue.Str l && right instanceof JsValue.Str r) {
            return l.value().equals(r.value());
        }
        if (left instanceof JsValue.Bool l && right instanceof JsValue.Bool r) {
            return l.value() == r.value();
        }
        return left == right;
    }

    /** {@code JSON.stringify(value)}；顶层为 undefined 或函数时抛 {@link JsError}。 */
    public static String stringify(JsValue value) {
        StringBuilder out = new StringBuilder();
        if (!writeJson(value, out)) {
            throw new JsError("Cannot read properties of undefined (reading 'length')");
        }
        return out.toString();
    }

    private static boolean writeJson(JsValue value, StringBuilder out) {
        switch (value) {
            case JsValue.Undefined u -> {
                return false;
            }
            case JsValue.Function f -> {
                return false;
            }
            case JsValue.Null n -> out.append("null");
            case JsValue.Bool b -> out.append(b.value());
            case JsValue.Num n -> out.append(JsNumbers.isFinite(n.value()) ? JsNumbers.toString(n.value()) : "null");
            case JsValue.Str s -> quote(s.value(), out);
            case JsDate d -> {
                String text = d.toJson();
                if (text == null) {
                    out.append("null");
                } else {
                    quote(text, out);
                }
            }
            case JsValue.Array a -> {
                out.append('[');
                boolean first = true;
                for (JsValue item : a.items()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    if (!writeJson(item, out)) {
                        out.append("null");
                    }
                }
                out.append(']');
            }
            case JsValue.Obj o -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, JsValue> entry : o.props().entrySet()) {
                    StringBuilder item = new StringBuilder();
                    quote(entry.getKey(), item);
                    item.append(':');
                    if (writeJson(entry.getValue(), item)) {
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        out.append(item);
                    }
                }
                out.append('}');
            }
        }
        return true;
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    boolean loneHigh = Character.isHighSurrogate(c)
                            && !(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)));
                    boolean loneLow =
                            Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(text.charAt(i - 1)));
                    boolean lone = loneHigh || loneLow;
                    if (c < 0x20 || lone) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** {@code text.replace(new RegExp(escaped(pattern), 'g'), replacement)}。 */
    public static String replaceAll(String text, String pattern, String replacement) {
        if (pattern.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        int index;
        while ((index = text.indexOf(pattern, from)) >= 0) {
            out.append(text, from, index);
            substitute(replacement, text, index, index + pattern.length(), out);
            from = index + pattern.length();
        }
        return out.append(text, from, text.length()).toString();
    }

    private static void substitute(String replacement, String text, int start, int end, StringBuilder out) {
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '$' && i + 1 < replacement.length()) {
                char next = replacement.charAt(i + 1);
                switch (next) {
                    case '$' -> out.append('$');
                    case '&' -> out.append(text, start, end);
                    case '`' -> out.append(text, 0, start);
                    case '\'' -> out.append(text, end, text.length());
                    default -> {
                        out.append(c);
                        continue;
                    }
                }
                i++;
                continue;
            }
            out.append(c);
        }
    }

    /** 转为 Java 值：对象为 Map，数组为 List，数字为 Double，null 与 undefined 为 null。 */
    public static @Nullable Object toPlain(JsValue value) {
        return switch (value) {
            case JsValue.Undefined u -> null;
            case JsValue.Null n -> null;
            case JsValue.Bool b -> b.value();
            case JsValue.Num n -> n.value();
            case JsValue.Str s -> s.value();
            case JsValue.Array a -> {
                List<@Nullable Object> list = new ArrayList<>();
                for (JsValue item : a.items()) {
                    list.add(toPlain(item));
                }
                yield list;
            }
            case JsValue.Obj o -> {
                Map<String, @Nullable Object> map = new LinkedHashMap<>();
                o.props().forEach((k, v) -> map.put(k, toPlain(v)));
                yield map;
            }
            default -> value;
        };
    }

    public static JsValue deepCopy(JsValue value) {
        return switch (value) {
            case JsValue.Array a -> {
                JsValue.Array copy = new JsValue.Array();
                a.items().forEach(item -> copy.items().add(deepCopy(item)));
                yield copy;
            }
            case JsValue.Obj o
            when !(o instanceof JsValue.ArrayInstance) -> {
                JsValue.Obj copy = new JsValue.Obj(o.cls());
                o.props().forEach((k, v) -> copy.props().put(k, deepCopy(v)));
                yield copy;
            }
            default -> value;
        };
    }
}
