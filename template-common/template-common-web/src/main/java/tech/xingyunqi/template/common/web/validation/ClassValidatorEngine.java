package tech.xingyunqi.template.common.web.validation;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.web.validation.js.Js;
import tech.xingyunqi.template.common.web.validation.js.JsDate;
import tech.xingyunqi.template.common.web.validation.js.JsJson;
import tech.xingyunqi.template.common.web.validation.js.JsValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 按 validation.json 复刻 nestjs ValidationPipe（class-transformer + class-validator）。 */
public final class ClassValidatorEngine {

    static final String UNKNOWN_VALUE_MESSAGE = "an unknown value was passed to the validate function";
    static final String OBJECT_EXPECTED_MESSAGE = "Validation failed (object is expected)";

    private static final Set<String> PRIMITIVE_TARGETS = Set.of("String", "Number", "Boolean", "Date");
    private static final Set<String> PROTO_KEYS = Set.of("__proto__", "prototype", "constructor");
    private static final Set<String> OBJECT_PROTOTYPE_FUNCTIONS = Set.of(
            "constructor",
            "__defineGetter__",
            "__defineSetter__",
            "__lookupGetter__",
            "__lookupSetter__",
            "hasOwnProperty",
            "isPrototypeOf",
            "propertyIsEnumerable",
            "toLocaleString",
            "toString",
            "valueOf");

    private final Metadata metadata;

    private ClassValidatorEngine(Metadata metadata) {
        this.metadata = metadata;
    }

    /** 解析 validation.json；文件中出现而未实现的约束或 transform 函数立即抛 IllegalStateException。 */
    public static ClassValidatorEngine load(InputStream validationJson) {
        JsonNode raw = JsonMapper.builder().build().readTree(validationJson);
        return new ClassValidatorEngine(Metadata.parse(raw));
    }

    public boolean hasDto(String dtoName) {
        return metadata.classes().containsKey(dtoName);
    }

    /** ValidationPipe.transform：校验失败抛 {@link ValidationFailedException}，成功返回转换后的实例。 */
    public JsonNode run(String dtoName, @Nullable JsonNode value, ParamType paramType) {
        Metadata.ClassMeta meta = metadata.classes().get(dtoName);
        if (meta == null) {
            throw new IllegalArgumentException(dtoName + " 不在 validation.json 中");
        }
        JsValue original = value == null ? JsValue.UNDEFINED : JsJson.fromNode(value);
        JsValue input = Js.isNullish(original) ? new JsValue.Obj() : original;
        boolean primitive = Js.isPrimitive(input);
        stripProtoKeys(input);
        JsValue transformed = transform(input, dtoName);
        boolean replaced = !(transformed instanceof JsValue.Obj obj && obj.cls() != null);
        JsValue.Obj entity;
        if (transformed instanceof JsValue.Obj obj && obj.cls() != null) {
            entity = obj;
        } else if (transformed instanceof JsValue.Array array) {
            entity = new JsValue.ArrayInstance(dtoName, array.items());
        } else {
            entity = new JsValue.Obj(dtoName);
        }
        ObjectNode errors = validate(entity);
        int status = metadata.pipe().errorHttpStatusCode();
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(status, errors);
        }
        if (replaced
                && (primitive || input instanceof JsValue.Array)
                && !meta.rulesByProperty().isEmpty()) {
            ObjectNode error = JsonNodeFactory.instance.objectNode();
            error.put(paramType.key(), OBJECT_EXPECTED_MESSAGE);
            throw new ValidationFailedException(status, error);
        }
        if (primitive) {
            return JsJson.toNode(original);
        }
        if (entity instanceof JsValue.ArrayInstance array) {
            JsValue.Array items = new JsValue.Array();
            for (int i = 0; i < array.length(); i++) {
                JsValue item = array.props().get(Integer.toString(i));
                items.items().add(item == null ? JsValue.NULL : item);
            }
            return JsJson.toNode(items);
        }
        return JsJson.toNode(entity);
    }

    private static void stripProtoKeys(JsValue value) {
        Deque<JsValue> stack = new ArrayDeque<>();
        stack.push(value);
        while (!stack.isEmpty()) {
            JsValue current = stack.pop();
            if (current instanceof JsValue.Array array) {
                array.items().forEach(stack::push);
            } else if (current instanceof JsValue.Obj obj) {
                obj.props().keySet().removeAll(PROTO_KEYS);
                obj.props().values().forEach(stack::push);
            }
        }
    }

    private JsValue plainToInstance(String cls, JsValue value) {
        return transform(value, cls);
    }

    private JsValue transform(JsValue value, @Nullable String target) {
        if (value instanceof JsValue.Array array) {
            JsValue.Array result = new JsValue.Array();
            for (JsValue item : array.items()) {
                result.items().add(transform(item, target));
            }
            return result;
        }
        if (target != null && PRIMITIVE_TARGETS.contains(target)) {
            if (Js.isNullish(value)) {
                return value;
            }
            return switch (target) {
                case "String" -> JsValue.str(Js.toStr(value));
                case "Number" -> JsValue.num(Js.toNumber(value));
                case "Date" -> JsDate.of(value);
                default -> JsValue.bool(Js.truthy(value));
            };
        }
        if (!(value instanceof JsValue.Obj source)) {
            return value;
        }
        Metadata.ClassMeta meta = target == null ? null : metadata.classes().get(target);
        JsValue.Obj result = new JsValue.Obj(target);
        if (meta != null) {
            meta.initialValues().forEach((k, v) -> result.props().put(k, Js.deepCopy(v)));
        }
        for (String key : List.copyOf(source.props().keySet())) {
            if (key.equals("__proto__") || OBJECT_PROTOTYPE_FUNCTIONS.contains(key)) {
                continue;
            }
            Metadata.Property property = meta == null ? null : meta.properties().get(key);
            String subTarget = property == null ? null : property.typeTarget();
            JsValue result0 = transform(source.get(key), subTarget);
            if (property != null && !property.transforms().isEmpty()) {
                Functions.TransformContext context =
                        new Functions.TransformContext(key, subTarget, this::plainToInstance);
                for (Functions.Transform fn : property.transforms()) {
                    result0 = fn.apply(result0, context);
                }
            }
            result.props().put(key, result0);
        }
        return result;
    }

    private static final class ErrorNode {
        private final String property;
        private final Map<String, String> constraints = new LinkedHashMap<>();
        private List<ErrorNode> children = new ArrayList<>();

        ErrorNode(String property) {
            this.property = property;
        }
    }

    private ObjectNode validate(JsValue.Obj entity) {
        List<ErrorNode> errors = new ArrayList<>();
        execute(entity, errors);
        return generate(strip(errors));
    }

    private static String className(JsValue value) {
        if (value instanceof JsValue.Obj obj && obj.cls() != null) {
            return obj.cls();
        }
        return value instanceof JsValue.Array ? "Array" : "Object";
    }

    private void execute(JsValue obj, List<ErrorNode> errors) {
        Metadata.ClassMeta meta = metadata.classes().get(className(obj));
        Map<String, List<Metadata.Rule>> grouped = meta == null ? Map.of() : meta.rulesByProperty();
        if (grouped.isEmpty() && metadata.pipe().forbidUnknownValues()) {
            ErrorNode error = new ErrorNode("undefined");
            error.constraints.put("unknownValue", UNKNOWN_VALUE_MESSAGE);
            errors.add(error);
            return;
        }
        if (metadata.pipe().whitelist() && obj instanceof JsValue.Obj object) {
            object.props().keySet().retainAll(grouped.keySet());
        }
        for (Map.Entry<String, List<Metadata.Rule>> entry : grouped.entrySet()) {
            String name = entry.getKey();
            JsValue value = obj instanceof JsValue.Obj object ? object.read(name) : JsValue.UNDEFINED;
            List<Metadata.Rule> defined = new ArrayList<>();
            List<Metadata.Rule> rest = new ArrayList<>();
            for (Metadata.Rule rule : entry.getValue()) {
                if (rule.type().equals(Metadata.IS_DEFINED)) {
                    defined.add(rule);
                } else if (!rule.type().equals(Metadata.WHITELIST)) {
                    rest.add(rule);
                }
            }
            perform(obj, value, name, defined, rest, errors);
        }
    }

    private void perform(
            @Nullable JsValue obj,
            JsValue value,
            String name,
            List<Metadata.Rule> defined,
            List<Metadata.Rule> rules,
            List<ErrorNode> errors) {
        ErrorNode error = new ErrorNode(name);
        errors.add(error);
        Map<String, JsValue> conditionObject = obj instanceof JsValue.Obj object ? object.props() : Map.of();
        boolean canValidate = true;
        for (Metadata.Rule rule : rules) {
            Functions.Condition condition = rule.condition();
            if (rule.type().equals(Metadata.CONDITIONAL_VALIDATION) && condition != null) {
                canValidate &= condition.test(conditionObject, value);
            }
        }
        if (!canValidate) {
            return;
        }
        custom(obj, value, defined, error);
        custom(obj, value, ofType(rules, Metadata.CUSTOM_VALIDATION), error);
        nested(value, ofType(rules, Metadata.NESTED_VALIDATION), error);
    }

    private static List<Metadata.Rule> ofType(List<Metadata.Rule> rules, String type) {
        return rules.stream().filter(r -> r.type().equals(type)).toList();
    }

    private void custom(@Nullable JsValue obj, JsValue value, List<Metadata.Rule> rules, ErrorNode error) {
        for (Metadata.Rule rule : rules) {
            Constraints.Constraint constraint = rule.constraint();
            if (constraint == null) {
                throw new IllegalStateException("constraint " + rule.key() + " 未解析");
            }
            boolean valid;
            if (rule.each() && value instanceof JsValue.Array array) {
                valid = true;
                for (JsValue item : array.items()) {
                    valid &= constraint.validate().test(item, rule.constraints());
                }
            } else {
                valid = constraint.validate().test(value, rule.constraints());
            }
            if (!valid) {
                error.constraints.put(rule.key(), message(obj, value, rule, constraint));
            }
        }
    }

    private void nested(JsValue value, List<Metadata.Rule> rules, ErrorNode error) {
        if (value == JsValue.UNDEFINED) {
            return;
        }
        for (Metadata.Rule rule : rules) {
            if (value instanceof JsValue.Array array) {
                List<JsValue> items = array.items();
                for (int index = 0; index < items.size(); index++) {
                    perform(value, items.get(index), Integer.toString(index), List.of(), rules, error.children);
                }
            } else if (value instanceof JsValue.Obj || value instanceof JsValue.Function) {
                execute(value, error.children);
            } else {
                error.constraints.put(rule.key(), message(null, value, rule, null));
            }
        }
    }

    private static String message(
            @Nullable JsValue obj, JsValue value, Metadata.Rule rule, Constraints.@Nullable Constraint constraint) {
        String message;
        String ruleMessage = rule.message();
        Constraints.DynamicMessage dynamic = constraint == null ? null : constraint.message();
        if (ruleMessage != null && !ruleMessage.isEmpty()) {
            message = ruleMessage;
        } else if (dynamic != null) {
            message = dynamic.apply(value, rule.constraints(), rule.each() ? "each value in " : "");
        } else {
            String defaultMessage = rule.defaultMessage();
            message = defaultMessage == null ? "" : defaultMessage;
        }
        List<JsValue> constraints = rule.constraints();
        for (int index = 0; index < constraints.size(); index++) {
            message = Js.replaceAll(message, "$constraint" + (index + 1), constraintToString(constraints.get(index)));
        }
        if (Js.isPrimitive(value)) {
            message = Js.replaceAll(message, "$value", Js.toStr(value));
        }
        message = Js.replaceAll(message, "$property", rule.propertyName());
        return Js.replaceAll(message, "$target", obj == null ? "Function" : className(obj));
    }

    private static String constraintToString(JsValue value) {
        if (value instanceof JsValue.Array array) {
            List<String> parts = new ArrayList<>();
            for (JsValue item : array.items()) {
                parts.add(Js.toStr(constraintValue(item)));
            }
            return String.join(", ", parts);
        }
        return Js.toStr(constraintValue(value));
    }

    private static JsValue constraintValue(JsValue value) {
        if (value instanceof JsValue.Obj obj) {
            Map<String, JsValue> props = obj.props();
            if (props.containsKey("regex") && props.containsKey("flags")) {
                return JsValue.str("/" + Js.toStr(obj.get("regex")) + "/" + Js.toStr(obj.get("flags")));
            }
            if (props.containsKey("source") && props.containsKey("function")) {
                return obj.get("source");
            }
        }
        return value;
    }

    private static List<ErrorNode> strip(List<ErrorNode> errors) {
        List<ErrorNode> kept = new ArrayList<>();
        for (ErrorNode error : errors) {
            error.children = strip(error.children);
            if (!error.constraints.isEmpty() || !error.children.isEmpty()) {
                kept.add(error);
            }
        }
        return kept;
    }

    private static ObjectNode generate(List<ErrorNode> errors) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (ErrorNode error : errors) {
            if (!error.children.isEmpty()) {
                result.set(error.property, generate(error.children));
            } else {
                result.put(error.property, String.join(", ", error.constraints.values()));
            }
        }
        return result;
    }
}
