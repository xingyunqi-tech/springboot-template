package tech.xingyunqi.template.common.web.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.web.validation.js.Js;
import tech.xingyunqi.template.common.web.validation.js.JsError;
import tech.xingyunqi.template.common.web.validation.js.JsJson;
import tech.xingyunqi.template.common.web.validation.js.JsValue;

/** DTO 中 {@code @Transform} 与 {@code @ValidateIf} 函数的实现，按规范化后的 JS 源码登记。 */
final class Functions {

    interface Transform {
        JsValue apply(JsValue value, TransformContext context);
    }

    interface Condition {
        boolean test(Map<String, JsValue> object, JsValue value);
    }

    record TransformContext(String key, @Nullable String typeTarget, BiFunction<String, JsValue, JsValue> toInstance) {}

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final Map<String, Transform> TRANSFORMS = new HashMap<>();
    private static final Map<String, Condition> CONDITIONS = new HashMap<>();

    private Functions() {}

    static String normalize(String source) {
        return WHITESPACE_RUN.matcher(source).replaceAll(" ").strip();
    }

    static @Nullable Transform transform(String source) {
        return TRANSFORMS.get(normalize(source));
    }

    static @Nullable Condition condition(String source) {
        return CONDITIONS.get(normalize(source));
    }

    private static void transform(String source, Transform transform) {
        TRANSFORMS.put(normalize(source), transform);
    }

    private static void condition(String source, Condition condition) {
        CONDITIONS.put(normalize(source), condition);
    }

    private static JsValue get(Map<String, JsValue> object, String key) {
        JsValue value = object.get(key);
        return value == null ? JsValue.UNDEFINED : value;
    }

    private static Transform numberOr(int fallback) {
        return (value, ctx) -> JsValue.num(Js.truthy(value) ? Js.toNumber(value) : fallback);
    }

    private static Transform blankTo(JsValue fallback, boolean nullBlank) {
        return (value, ctx) -> {
            boolean blank = value == JsValue.UNDEFINED
                    || (value instanceof JsValue.Str s && s.value().isEmpty())
                    || (nullBlank && value == JsValue.NULL);
            return blank ? fallback : JsValue.num(Js.toNumber(value));
        };
    }

    private static JsValue lower(JsValue value, TransformContext ctx) {
        return value instanceof JsValue.Str s ? JsValue.str(s.value().toLowerCase(Locale.ROOT)) : value;
    }

    private static JsValue upper(JsValue value, TransformContext ctx) {
        return value instanceof JsValue.Str s ? JsValue.str(s.value().toUpperCase(Locale.ROOT)) : value;
    }

    private static JsValue trim(JsValue value, TransformContext ctx) {
        return value instanceof JsValue.Str s ? JsValue.str(Js.trim(s.value())) : value;
    }

    private static JsValue lowerTrimOptional(JsValue value, TransformContext ctx) {
        if (Js.isNullish(value)) {
            return JsValue.UNDEFINED;
        }
        if (!(value instanceof JsValue.Str s)) {
            throw new JsError("params.value?.toLowerCase is not a function");
        }
        return JsValue.str(Js.trim(s.value().toLowerCase(Locale.ROOT)));
    }

    private static JsValue sanitize(JsValue value, TransformContext ctx) {
        if (!(value instanceof JsValue.Str s)) {
            return value;
        }
        return JsValue.str(Js.trim(TAG.matcher(s.value()).replaceAll("")));
    }

    private static JsValue lowerEach(JsValue value, TransformContext ctx) {
        if (!(value instanceof JsValue.Array array)) {
            return value;
        }
        JsValue.Array result = new JsValue.Array();
        for (JsValue item : array.items()) {
            result.items().add(lower(item, ctx));
        }
        return result;
    }

    private static JsValue stringBool(JsValue value, TransformContext ctx) {
        if (value instanceof JsValue.Str s) {
            if (s.value().equals("true")) {
                return JsValue.TRUE;
            }
            if (s.value().equals("false")) {
                return JsValue.FALSE;
            }
        }
        return value;
    }

    private static Transform trimOr(JsValue fallback) {
        return (value, ctx) -> {
            if (!(value instanceof JsValue.Str s)) {
                return value;
            }
            String trimmed = Js.trim(s.value());
            return trimmed.isEmpty() ? fallback : JsValue.str(trimmed);
        };
    }

    private static JsValue jsonQuery(JsValue value, TransformContext ctx) {
        if (!Js.truthy(value)) {
            return JsValue.UNDEFINED;
        }
        String target = ctx.typeTarget();
        if (target == null) {
            throw new IllegalStateException(ctx.key() + " 的 JSON 查询转换缺少 @Type 目标类");
        }
        JsValue parsed;
        try {
            parsed = JsJson.parse(Js.toStr(value));
        } catch (JsError e) {
            return value;
        }
        return ctx.toInstance().apply(target, parsed);
    }

    private static JsValue splitList(JsValue value, TransformContext ctx) {
        List<JsValue> items = value instanceof JsValue.Array array ? array.items() : List.of(value);
        List<JsValue> parts = new ArrayList<>();
        for (JsValue item : items) {
            for (String part : Js.toStr(item).split(",", -1)) {
                String trimmed = Js.trim(part);
                if (!trimmed.isEmpty()) {
                    parts.add(JsValue.str(trimmed));
                }
            }
        }
        return new JsValue.Array(parts);
    }

    private static boolean allRegionCodes(Map<String, JsValue> object) {
        return Js.truthy(get(object, "provinceCode"))
                && Js.truthy(get(object, "cityCode"))
                && Js.truthy(get(object, "districtCode"));
    }

    static {
        transform("({ value }) => (value ? Number(value) : 1)", numberOr(1));
        transform("({ value }) => (value ? Number(value) : 10)", numberOr(10));
        transform("({ value }) => (value ? Number(value) : 20)", numberOr(20));
        transform("({ value }) => (value ? Number(value) : 50)", numberOr(50));
        transform("({ value }) => (typeof value === 'string' ? value.toLowerCase() : value)", Functions::lower);
        transform("({ value }) => typeof value === 'string' ? value.toLowerCase() : value", Functions::lower);
        transform("(params) => params.value?.toLowerCase().trim()", Functions::lowerTrimOptional);
        transform(
                "(params) => { if (typeof params.value !== 'string') { return params.value; } "
                        + "return params.value .replace(/<[^>]*>/g, '') .trim(); }",
                Functions::sanitize);
        transform(
                "({ value }) => Array.isArray(value) ? value.map((item) => "
                        + "(typeof item === 'string' ? item.toLowerCase() : item)) : value",
                Functions::lowerEach);
        transform("({ value }) => (typeof value === 'string' ? value.trim() : value)", Functions::trim);
        transform("({ value }) => value === 'true' ? true : value === 'false' ? false : value", Functions::stringBool);
        transform(
                "({ value }) => (value === 'true' ? true : value === 'false' ? false : value)", Functions::stringBool);
        transform(
                "({ value }) => { if (value === 'true') return true; "
                        + "if (value === 'false') return false; return value; }",
                Functions::stringBool);
        transform(
                "({ value }) => typeof value === 'string' ? value.trim() || undefined : value",
                trimOr(JsValue.UNDEFINED));
        transform(
                "({ value }) => (typeof value === 'string' ? value.trim() || undefined : value)",
                trimOr(JsValue.UNDEFINED));
        transform("({ value }) => typeof value === 'string' ? value.trim() || null : value", trimOr(JsValue.NULL));
        transform(
                "({ value }) => { if (!value) { return undefined; } try { return "
                        + "(0, class_transformer_1.plainToInstance)(cls, JSON.parse(value)); } "
                        + "catch { return value; } }",
                Functions::jsonQuery);
        transform(
                "({ value }) => [] .concat(value) .flatMap((item) => String(item).split(',')) "
                        + ".map((item) => item.trim()) .filter(Boolean)",
                Functions::splitList);
        transform(
                "({ value }) => (value === undefined || value === '' ? 0 : Number(value))",
                blankTo(JsValue.num(0), false));
        transform(
                "({ value }) => (value === undefined || value === '' ? "
                        + "exports.SEGMENT_LIMITS.defaultSize : Number(value))",
                blankTo(JsValue.num(10), false));
        transform(
                "({ value }) => value === '' || value === undefined || value === null " + "? undefined : Number(value)",
                blankTo(JsValue.UNDEFINED, true));
        transform(
                "({ value }) => value === undefined || value === '' ? undefined : Number(value)",
                blankTo(JsValue.UNDEFINED, false));
        transform("({ value }) => typeof value === 'string' ? value.toUpperCase() : value", Functions::upper);

        condition(
                "(object, value) => { return object[propertyName] !== null "
                        + "&& object[propertyName] !== undefined; }",
                (obj, value) -> !Js.isNullish(value));
        condition("(_, value) => value !== undefined", (obj, value) -> value != JsValue.UNDEFINED);
        condition("(_, value) => value !== null", (obj, value) -> value != JsValue.NULL);
        condition("(_, value) => value !== undefined && value !== null", (obj, value) -> !Js.isNullish(value));
        condition(
                "(dto) => dto.knowledgePointIds !== undefined",
                (obj, value) -> get(obj, "knowledgePointIds") != JsValue.UNDEFINED);
        condition("(obj) => !hasAllRegionCodes(obj)", (obj, value) -> !allRegionCodes(obj));
        condition(
                "(dto) => dto.reason === feedback_1.QuestionReportReason.OTHER || dto.content !== undefined",
                (obj, value) -> Js.strictEquals(get(obj, "reason"), JsValue.str("other"))
                        || get(obj, "content") != JsValue.UNDEFINED);
        condition("(dto) => dto.feedback !== null", (obj, value) -> get(obj, "feedback") != JsValue.NULL);
        condition("(dto) => dto.errataId !== null", (obj, value) -> get(obj, "errataId") != JsValue.NULL);
    }
}
