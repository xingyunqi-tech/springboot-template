package tech.xingyunqi.template.common.web.validation.js;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** JSON 与 {@link JsValue} 互转：{@code JSON.parse}、Jackson 树的读入与 {@code JSON.stringify} 形状的输出。 */
public final class JsJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final double INTEGER_LIMIT = 1e21;

    private JsJson() {}

    public static JsValue fromNode(JsonNode node) {
        if (node.isObject()) {
            JsValue.Obj obj = new JsValue.Obj();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                obj.props().put(entry.getKey(), fromNode(entry.getValue()));
            }
            return obj;
        }
        if (node.isArray()) {
            JsValue.Array array = new JsValue.Array();
            for (JsonNode item : node.values()) {
                array.items().add(fromNode(item));
            }
            return array;
        }
        if (node.isString()) {
            return JsValue.str(node.stringValue());
        }
        if (node.isNumber()) {
            return JsValue.num(node.doubleValue());
        }
        if (node.isBoolean()) {
            return JsValue.bool(node.booleanValue());
        }
        if (node.isNull()) {
            return JsValue.NULL;
        }
        if (node.isMissingNode()) {
            return JsValue.UNDEFINED;
        }
        throw new IllegalArgumentException("unsupported JSON node: " + node.getNodeType());
    }

    /** 按 {@code JSON.parse(JSON.stringify(value))} 的结果构造 Jackson 树；顶层不可序列化时返回 null 节点。 */
    public static JsonNode toNode(JsValue value) {
        JsonNode node = toNodeOrNull(value);
        return node == null ? NODES.nullNode() : node;
    }

    private static @Nullable JsonNode toNodeOrNull(JsValue value) {
        return switch (value) {
            case JsValue.Undefined u -> null;
            case JsValue.Function f -> null;
            case JsValue.Null n -> NODES.nullNode();
            case JsValue.Bool b -> NODES.booleanNode(b.value());
            case JsValue.Num n -> numberNode(n.value());
            case JsValue.Str s -> NODES.stringNode(s.value());
            case JsDate d -> {
                String text = d.toJson();
                yield text == null ? NODES.nullNode() : NODES.stringNode(text);
            }
            case JsValue.Array a -> {
                ArrayNode array = NODES.arrayNode();
                for (JsValue item : a.items()) {
                    array.add(toNode(item));
                }
                yield array;
            }
            case JsValue.Obj o -> {
                ObjectNode obj = NODES.objectNode();
                for (Map.Entry<String, JsValue> entry : o.props().entrySet()) {
                    JsonNode item = toNodeOrNull(entry.getValue());
                    if (item != null) {
                        obj.set(entry.getKey(), item);
                    }
                }
                yield obj;
            }
        };
    }

    /** 整数值（绝对值小于 1e21）输出为整数节点，与 JSON.stringify 的文本形状一致。 */
    public static JsonNode numberNode(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return NODES.nullNode();
        }
        if (value == Math.rint(value) && Math.abs(value) < INTEGER_LIMIT) {
            if (Math.abs(value) <= Integer.MAX_VALUE) {
                return NODES.numberNode((int) value);
            }
            if (Math.abs(value) < 0x1p63) {
                return NODES.numberNode((long) value);
            }
            BigInteger big = new BigDecimal(value).toBigInteger();
            return NODES.numberNode(big);
        }
        return NODES.numberNode(value);
    }

    /** {@code JSON.parse(text)}；语法错误抛 {@link JsError}。 */
    public static JsValue parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        JsValue value = parser.value();
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error();
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        JsError error() {
            return new JsError("Unexpected token in JSON at position " + pos);
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        char peek() {
            if (pos >= text.length()) {
                throw error();
            }
            return text.charAt(pos);
        }

        void expect(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw error();
            }
            pos += literal.length();
        }

        JsValue value() {
            return switch (peek()) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> JsValue.str(string());
                case 't' -> literal("true", JsValue.TRUE);
                case 'f' -> literal("false", JsValue.FALSE);
                case 'n' -> literal("null", JsValue.NULL);
                default -> number();
            };
        }

        JsValue literal(String text, JsValue value) {
            expect(text);
            return value;
        }

        JsValue object() {
            pos++;
            JsValue.Obj obj = new JsValue.Obj();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return obj;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error();
                }
                String key = string();
                skipWhitespace();
                expect(":");
                skipWhitespace();
                obj.props().put(key, value());
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == '}') {
                    return obj;
                }
                if (c != ',') {
                    throw error();
                }
            }
        }

        JsValue array() {
            pos++;
            JsValue.Array array = new JsValue.Array();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return array;
            }
            while (true) {
                skipWhitespace();
                array.items().add(value());
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == ']') {
                    return array;
                }
                if (c != ',') {
                    throw error();
                }
            }
        }

        String string() {
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = peek();
                pos++;
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error();
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char h = text.charAt(pos + i);
                            int digit = h < 128 ? Character.digit(h, 16) : -1;
                            if (digit < 0) {
                                throw error();
                            }
                            code = code * 16 + digit;
                        }
                        pos += 4;
                        out.append((char) code);
                    }
                    default -> throw error();
                }
            }
        }

        JsValue number() {
            int start = pos;
            if (pos < text.length() && text.charAt(pos) == '-') {
                pos++;
            }
            if (pos < text.length() && text.charAt(pos) == '0') {
                pos++;
            } else if (!digits()) {
                throw error();
            }
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                if (!digits()) {
                    throw error();
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                if (!digits()) {
                    throw error();
                }
            }
            return JsValue.num(Double.parseDouble(text.substring(start, pos)));
        }

        boolean digits() {
            int start = pos;
            while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
                pos++;
            }
            return pos > start;
        }
    }
}
