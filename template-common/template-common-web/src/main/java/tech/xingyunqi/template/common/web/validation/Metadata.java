package tech.xingyunqi.template.common.web.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.web.validation.js.JsJson;
import tech.xingyunqi.template.common.web.validation.js.JsValue;
import tools.jackson.databind.JsonNode;

/** validation.json 解析结果；所有约束与函数在解析时解析到实现，缺失即抛 {@link IllegalStateException}。 */
record Metadata(PipeOptions pipe, Map<String, ClassMeta> classes) {

    static final String CUSTOM_VALIDATION = "customValidation";
    static final String NESTED_VALIDATION = "nestedValidation";
    static final String CONDITIONAL_VALIDATION = "conditionalValidation";
    static final String IS_DEFINED = "isDefined";
    static final String WHITELIST = "whitelistValidation";

    record PipeOptions(boolean whitelist, boolean forbidUnknownValues, int errorHttpStatusCode) {}

    record Rule(
            String propertyName,
            String type,
            String key,
            List<JsValue> constraints,
            @Nullable String message,
            @Nullable String defaultMessage,
            boolean each,
            Constraints.@Nullable Constraint constraint,
            Functions.@Nullable Condition condition) {}

    record Property(String name, @Nullable String typeTarget, List<Functions.Transform> transforms, List<Rule> rules) {}

    record ClassMeta(
            String name,
            Map<String, JsValue> initialValues,
            Map<String, Property> properties,
            Map<String, List<Rule>> rulesByProperty) {}

    static Metadata parse(JsonNode raw) {
        JsonNode pipe = raw.required("pipe");
        PipeOptions options = new PipeOptions(
                pipe.required("whitelist").asBoolean(),
                pipe.required("forbidUnknownValues").asBoolean(),
                pipe.required("errorHttpStatusCode").asInt());
        Map<String, ClassMeta> classes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : raw.required("classes").properties()) {
            classes.put(entry.getKey(), classMeta(entry.getKey(), entry.getValue()));
        }
        return new Metadata(options, Map.copyOf(classes));
    }

    private static ClassMeta classMeta(String name, JsonNode raw) {
        Map<String, JsValue> initialValues = new LinkedHashMap<>();
        JsonNode initial = raw.path("initialValues");
        if (initial.isObject()) {
            for (Map.Entry<String, JsonNode> entry : initial.properties()) {
                initialValues.put(entry.getKey(), JsJson.fromNode(entry.getValue()));
            }
        }
        Map<String, Property> properties = new LinkedHashMap<>();
        Map<String, List<Rule>> rulesByProperty = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : raw.required("properties").properties()) {
            Property property = property(name, entry.getKey(), entry.getValue());
            properties.put(entry.getKey(), property);
            if (!property.rules().isEmpty()) {
                rulesByProperty.put(entry.getKey(), property.rules());
            }
        }
        return new ClassMeta(name, initialValues, properties, rulesByProperty);
    }

    private static Property property(String className, String name, JsonNode raw) {
        List<Functions.Transform> transforms = new ArrayList<>();
        for (JsonNode transform : raw.path("transforms").values()) {
            String source = transform.required("source").stringValue();
            Functions.Transform resolved = Functions.transform(source);
            if (resolved == null) {
                throw new IllegalStateException(className + "." + name + " 的 @Transform 未实现: " + source);
            }
            transforms.add(resolved);
        }
        JsonNode target = raw.path("type").path("target");
        String typeTarget = target.isString() ? target.stringValue() : null;
        List<Rule> rules = new ArrayList<>();
        for (JsonNode rule : raw.path("validations").values()) {
            rules.add(rule(className, name, rule));
        }
        return new Property(name, typeTarget, List.copyOf(transforms), List.copyOf(rules));
    }

    private static Rule rule(String className, String property, JsonNode raw) {
        String type = raw.required("type").stringValue();
        String key = raw.required("key").stringValue();
        List<JsValue> constraints = new ArrayList<>();
        for (JsonNode constraint : raw.path("constraints").values()) {
            constraints.add(JsJson.fromNode(constraint));
        }
        String message = text(raw.path("message"));
        String defaultMessage = text(raw.path("defaultMessage"));
        String where = className + "." + property;
        Constraints.Constraint constraint = null;
        Functions.Condition condition = null;
        switch (type) {
            case CUSTOM_VALIDATION, IS_DEFINED -> {
                constraint = Constraints.resolve(key, defaultMessage);
                if (constraint == null) {
                    throw new IllegalStateException(where + " 的约束未实现: " + key + " / " + defaultMessage);
                }
            }
            case CONDITIONAL_VALIDATION -> {
                JsonNode source = raw.path("constraints").path(0).path("source");
                condition = source.isString() ? Functions.condition(source.stringValue()) : null;
                if (condition == null) {
                    throw new IllegalStateException(where + " 的 @ValidateIf 未实现: " + source);
                }
            }
            case NESTED_VALIDATION, WHITELIST -> {}
            default -> throw new IllegalStateException(where + " 的校验类型未实现: " + type);
        }
        return new Rule(
                property,
                type,
                key,
                List.copyOf(constraints),
                message,
                defaultMessage,
                raw.path("each").asBoolean(),
                constraint,
                condition);
    }

    private static @Nullable String text(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }
}
