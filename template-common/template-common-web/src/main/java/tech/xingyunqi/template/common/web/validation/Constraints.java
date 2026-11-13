package tech.xingyunqi.template.common.web.validation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.json.JsNumbers;
import tech.xingyunqi.template.common.web.validation.js.Js;
import tech.xingyunqi.template.common.web.validation.js.JsDate;
import tech.xingyunqi.template.common.web.validation.js.JsError;
import tech.xingyunqi.template.common.web.validation.js.JsRegex;
import tech.xingyunqi.template.common.web.validation.js.JsValue;
import tech.xingyunqi.template.common.web.validation.js.ValidatorJs;

/** class-validator 0.15 内置约束与 nestjs 自定义约束，按导出的约束键注册。 */
final class Constraints {

    interface Validate {
        boolean test(JsValue value, List<JsValue> constraints);
    }

    interface DynamicMessage {
        String apply(JsValue value, List<JsValue> constraints, String eachPrefix);
    }

    record Constraint(Validate validate, @Nullable DynamicMessage message) {
        Constraint(Validate validate) {
            this(validate, null);
        }
    }

    static final String MD5_HASH_MESSAGE = "Password must be a valid MD5 hash (32 hexadecimal characters)";
    private static final int MAX_METADATA_JSON_LENGTH = 2048;

    private static final Map<String, JsRegex> REGEX_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Constraint> BUILTINS = new HashMap<>();
    private static final Map<String, Constraint> ANONYMOUS = Map.of(MD5_HASH_MESSAGE, new Constraint(Constraints::md5));

    private Constraints() {}

    static @Nullable Constraint resolve(String key, @Nullable String defaultMessage) {
        if (key.equals("customValidation")) {
            return defaultMessage == null ? null : ANONYMOUS.get(defaultMessage);
        }
        return BUILTINS.get(key);
    }

    private static JsValue at(List<JsValue> constraints, int index) {
        return constraints.size() > index ? constraints.get(index) : JsValue.UNDEFINED;
    }

    private static JsValue.@Nullable Obj options(List<JsValue> constraints) {
        return at(constraints, 0) instanceof JsValue.Obj obj ? obj : null;
    }

    private static @Nullable Object plainOptions(List<JsValue> constraints) {
        JsValue.Obj obj = options(constraints);
        return obj == null ? null : Js.toPlain(obj);
    }

    private static Map<String, @Nullable Object> lengthOptions(@Nullable JsValue min, @Nullable JsValue max) {
        Map<String, @Nullable Object> options = new HashMap<>();
        if (min != null) {
            options.put("min", Js.toPlain(min));
        }
        if (max != null) {
            options.put("max", Js.toPlain(max));
        }
        return options;
    }

    private static boolean isNumber(JsValue value, List<JsValue> constraints) {
        if (!(value instanceof JsValue.Num num)) {
            return false;
        }
        double v = num.value();
        JsValue.Obj options = options(constraints);
        if (Double.isInfinite(v)) {
            return options != null && Js.truthy(options.get("allowInfinity"));
        }
        if (Double.isNaN(v)) {
            return options != null && Js.truthy(options.get("allowNaN"));
        }
        JsValue maxPlaces = options == null ? JsValue.UNDEFINED : options.get("maxDecimalPlaces");
        if (!Js.isNullish(maxPlaces)) {
            int places = 0;
            if (v % 1 != 0) {
                String[] parts = JsNumbers.toString(v).split("\\.", -1);
                if (parts.length < 2) {
                    throw new JsError("Cannot read properties of undefined (reading 'length')");
                }
                places = parts[1].length();
            }
            if (places > Js.toNumber(maxPlaces)) {
                return false;
            }
        }
        return true;
    }

    private static boolean includes(List<JsValue> values, JsValue value) {
        if (value instanceof JsValue.Num num && Double.isNaN(num.value())) {
            return values.stream().anyMatch(v -> v instanceof JsValue.Num n && Double.isNaN(n.value()));
        }
        return values.stream().anyMatch(v -> Js.strictEquals(v, value));
    }

    private static boolean isEnum(JsValue value, List<JsValue> constraints) {
        JsValue entity = at(constraints, 0);
        List<JsValue> values =
                entity instanceof JsValue.Obj obj ? List.copyOf(obj.props().values()) : List.of();
        return includes(values, value);
    }

    private static boolean isIn(JsValue value, List<JsValue> constraints) {
        return at(constraints, 0) instanceof JsValue.Array possible
                && possible.items().stream().anyMatch(v -> Js.strictEquals(v, value));
    }

    private static boolean compare(JsValue value, List<JsValue> constraints, boolean min) {
        if (value instanceof JsValue.Num v && at(constraints, 0) instanceof JsValue.Num bound) {
            return min ? v.value() >= bound.value() : v.value() <= bound.value();
        }
        return false;
    }

    private static String isLengthMessage(JsValue value, List<JsValue> constraints, String eachPrefix) {
        JsValue low = at(constraints, 0);
        JsValue high = at(constraints, 1);
        boolean isMin = !Js.isNullish(low);
        boolean isMax = !Js.isNullish(high);
        Integer length = jsLength(value);
        if (isMin && (!Js.truthy(value) || (length != null && length < Js.toNumber(low)))) {
            return eachPrefix + "$property must be longer than or equal to $constraint1 characters";
        }
        if (isMax && length != null && length > Js.toNumber(high)) {
            return eachPrefix + "$property must be shorter than or equal to $constraint2 characters";
        }
        return eachPrefix
                + "$property must be longer than or equal to $constraint1 and shorter than or equal to "
                + "$constraint2 characters";
    }

    private static @Nullable Integer jsLength(JsValue value) {
        if (value instanceof JsValue.Str s) {
            return s.value().length();
        }
        if (value instanceof JsValue.Array a) {
            return a.items().size();
        }
        return null;
    }

    private static boolean matches(JsValue value, List<JsValue> constraints) {
        if (!(value instanceof JsValue.Str s)) {
            return false;
        }
        JsValue pattern = at(constraints, 0);
        String source;
        String flags;
        if (pattern instanceof JsValue.Obj obj) {
            source = Js.toStr(obj.get("regex"));
            flags = Js.toStr(obj.get("flags"));
        } else {
            JsValue modifiers = at(constraints, 1);
            source = Js.toStr(pattern);
            flags = Js.truthy(modifiers) ? Js.toStr(modifiers) : "";
        }
        JsRegex regex = REGEX_CACHE.computeIfAbsent(flags + "/" + source, k -> JsRegex.unicodeCase(source, flags));
        return regex.test(s.value());
    }

    private static boolean isUuid(JsValue value, List<JsValue> constraints) {
        if (!(value instanceof JsValue.Str s)) {
            return false;
        }
        JsValue version = constraints.isEmpty() ? JsValue.NULL : constraints.get(0);
        if (version instanceof JsValue.Array versions) {
            return versions.items().stream().anyMatch(v -> ValidatorJs.isUUID(s.value(), Js.toPlain(v)));
        }
        return ValidatorJs.isUUID(s.value(), Js.toPlain(version));
    }

    private static int size(JsValue value) {
        return value instanceof JsValue.Array array ? array.items().size() : -1;
    }

    private static boolean arrayUnique(JsValue value, List<JsValue> constraints) {
        if (!(value instanceof JsValue.Array array)) {
            return false;
        }
        List<JsValue> items = array.items();
        for (int i = 0; i < items.size(); i++) {
            int first = -1;
            for (int j = 0; j < items.size(); j++) {
                if (Js.strictEquals(items.get(j), items.get(i))) {
                    first = j;
                    break;
                }
            }
            if (first != i) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRegionCode(JsValue value, List<JsValue> constraints) {
        if (Js.isNullish(value)) {
            return true;
        }
        return value instanceof JsValue.Str s
                && s.value().length() == 12
                && s.value().chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static boolean maxJsonLength(JsValue value, List<JsValue> constraints) {
        return Js.stringify(value).length() <= MAX_METADATA_JSON_LENGTH;
    }

    private static boolean md5(JsValue value, List<JsValue> constraints) {
        return value instanceof JsValue.Str s
                && s.value().length() == 32
                && s.value().chars().allMatch(c -> Character.digit(c, 16) >= 0 && c < 128);
    }

    private static void builtin(String key, Validate validate) {
        BUILTINS.put(key, new Constraint(validate));
    }

    static {
        builtin("isDefined", (v, c) -> !Js.isNullish(v));
        builtin(
                "isNotEmpty",
                (v, c) -> !(v instanceof JsValue.Str s && s.value().isEmpty()) && !Js.isNullish(v));
        builtin("isString", (v, c) -> v instanceof JsValue.Str);
        builtin(
                "isInt",
                (v, c) -> v instanceof JsValue.Num n
                        && JsNumbers.isFinite(n.value())
                        && n.value() == Math.rint(n.value()));
        builtin("isNumber", Constraints::isNumber);
        builtin("isBoolean", (v, c) -> v instanceof JsValue.Bool);
        builtin("isArray", (v, c) -> v instanceof JsValue.Array);
        builtin("isObject", (v, c) -> v instanceof JsValue.Obj || v instanceof JsValue.Function);
        builtin("isDate", (v, c) -> v instanceof JsDate date && date.valid());
        builtin("isEnum", Constraints::isEnum);
        builtin("isIn", Constraints::isIn);
        builtin("min", (v, c) -> compare(v, c, true));
        builtin("max", (v, c) -> compare(v, c, false));
        builtin(
                "maxLength",
                (v, c) -> v instanceof JsValue.Str s
                        && ValidatorJs.isLength(s.value(), lengthOptions(JsValue.num(0), at(c, 0))));
        builtin(
                "minLength",
                (v, c) -> v instanceof JsValue.Str s && ValidatorJs.isLength(s.value(), lengthOptions(at(c, 0), null)));
        BUILTINS.put(
                "isLength",
                new Constraint(
                        (v, c) -> v instanceof JsValue.Str s
                                && ValidatorJs.isLength(s.value(), lengthOptions(at(c, 0), at(c, 1))),
                        Constraints::isLengthMessage));
        builtin("matches", Constraints::matches);
        builtin("isUuid", Constraints::isUuid);
        builtin("isEmail", (v, c) -> v instanceof JsValue.Str s && ValidatorJs.isEmail(s.value(), plainOptions(c)));
        builtin("isUrl", (v, c) -> v instanceof JsValue.Str s && ValidatorJs.isURL(s.value(), plainOptions(c)));
        builtin("isIso8601", (v, c) -> v instanceof JsValue.Str s && ValidatorJs.isISO8601(s.value(), plainOptions(c)));
        builtin(
                "isDateString",
                (v, c) -> v instanceof JsValue.Str s && ValidatorJs.isISO8601(s.value(), plainOptions(c)));
        builtin("arrayMaxSize", (v, c) -> size(v) >= 0 && size(v) <= Js.toNumber(at(c, 0)));
        builtin("arrayMinSize", (v, c) -> size(v) >= 0 && size(v) >= Js.toNumber(at(c, 0)));
        builtin("arrayNotEmpty", (v, c) -> size(v) > 0);
        builtin("arrayUnique", Constraints::arrayUnique);
        builtin("isRegionCode", Constraints::isRegionCode);
        builtin("maxJsonLength", Constraints::maxJsonLength);
    }
}
