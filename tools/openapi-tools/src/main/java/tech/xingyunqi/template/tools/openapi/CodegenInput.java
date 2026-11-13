package tech.xingyunqi.template.tools.openapi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** 生成代码用的 OpenAPI 输入：只保留 scope 内的 operation 与其引用的 schema，并应用 {@code codegen-overlay.yaml}。 */
public final class CodegenInput {

    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private CodegenInput() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "用法：CodegenInput <baseline.json> <scope.yaml> <codegen-overlay.yaml> <out.json>");
        }
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode baseline = (ObjectNode) json.readTree(Files.readString(Path.of(args[0])));
        Scope scope = Scope.load(Path.of(args[1]));
        JsonNode overlay = YAMLMapper.builder().build().readTree(Files.readString(Path.of(args[2])));
        ObjectNode result = build(baseline, scope, overlay);
        Path out = Path.of(args[3]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(
                out, json.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardCharsets.UTF_8);
    }

    static ObjectNode build(ObjectNode baseline, Scope scope, JsonNode overlay) {
        ObjectNode document = baseline.deepCopy();
        Set<String> found = new LinkedHashSet<>();
        ObjectNode paths = document.putObject("paths");
        for (Map.Entry<String, JsonNode> path : baseline.path("paths").properties()) {
            ObjectNode kept = paths.objectNode();
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                String id = operation.getValue().path("operationId").stringValue("");
                if (METHODS.contains(operation.getKey()) && scope.operations().containsKey(id)) {
                    ObjectNode copy = (ObjectNode) operation.getValue().deepCopy();
                    applyOperationOverlay(copy, overlay.path("operations").path(id));
                    kept.set(operation.getKey(), copy);
                    found.add(id);
                }
            }
            if (!kept.isEmpty()) {
                paths.set(path.getKey(), kept);
            }
        }
        Set<String> missing = new LinkedHashSet<>(scope.operations().keySet());
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("scope.yaml 中的 operationId 不在基线中：" + missing);
        }
        applySchemaOverlay(document, overlay.path("schemas"));
        pruneSchemas(document);
        return document;
    }

    private static void applyOperationOverlay(ObjectNode operation, JsonNode overlay) {
        for (Map.Entry<String, JsonNode> response : overlay.path("responses").properties()) {
            ObjectNode target = operation.withObject("/responses").withObject("/" + response.getKey());
            if (!target.has("description")) {
                target.put("description", "");
            }
            target.putObject("content")
                    .putObject("application/json")
                    .set("schema", response.getValue().deepCopy());
        }
    }

    private static void applySchemaOverlay(ObjectNode document, JsonNode overlay) {
        ObjectNode schemas = document.withObject("/components").withObject("/schemas");
        for (Map.Entry<String, JsonNode> entry : overlay.properties()) {
            JsonNode schema = schemas.get(entry.getKey());
            if (!(schema instanceof ObjectNode object)) {
                throw new IllegalStateException("codegen-overlay.yaml 引用了不存在的 schema：" + entry.getKey());
            }
            for (JsonNode field : entry.getValue().path("nullable")) {
                property(object, entry.getKey(), field.stringValue()).put("nullable", true);
            }
            for (JsonNode field : entry.getValue().path("optional")) {
                property(object, entry.getKey(), field.stringValue());
                removeRequired(object, field.stringValue());
            }
        }
    }

    private static ObjectNode property(ObjectNode schema, String schemaName, String field) {
        JsonNode property = schema.path("properties").get(field);
        if (!(property instanceof ObjectNode object)) {
            throw new IllegalStateException("codegen-overlay.yaml 引用了不存在的字段：" + schemaName + "." + field);
        }
        return object;
    }

    private static void removeRequired(ObjectNode schema, String field) {
        if (schema.get("required") instanceof ArrayNode required) {
            for (int i = required.size() - 1; i >= 0; i--) {
                if (field.equals(required.get(i).stringValue())) {
                    required.remove(i);
                }
            }
        }
    }

    private static void pruneSchemas(ObjectNode document) {
        JsonNode schemas = document.path("components").path("schemas");
        if (!(schemas instanceof ObjectNode all)) {
            return;
        }
        Set<String> reachable = new LinkedHashSet<>();
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(document.path("paths"));
        while (!queue.isEmpty()) {
            JsonNode node = queue.poll();
            if (node.isObject()) {
                JsonNode ref = node.get("$ref");
                if (ref != null && ref.isString() && ref.stringValue().startsWith("#/components/schemas/")) {
                    String name = ref.stringValue().substring("#/components/schemas/".length());
                    if (reachable.add(name) && all.has(name)) {
                        queue.add(all.get(name));
                    }
                }
                node.values().forEach(queue::add);
            } else if (node.isArray()) {
                node.values().forEach(queue::add);
            }
        }
        for (Iterator<String> names = all.propertyNames().iterator(); names.hasNext(); ) {
            if (!reachable.contains(names.next())) {
                names.remove();
            }
        }
    }
}
