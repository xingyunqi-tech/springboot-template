package tech.xingyunqi.template.tools.openapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** {@code openapi/scope.yaml}：本期实现的 operationId 及其权限声明（照抄 nestjs controller）。 */
public record Scope(Map<String, Operation> operations) {

    /** {@code permissions} 为 null 表示不经过权限守卫；{@code anyOf} 对应 {@code @RequireAnyPermission}。 */
    public record Operation(
            String operationId,
            @org.jspecify.annotations.Nullable List<String> permissions,
            boolean anyOf) {}

    public static Scope load(Path file) throws IOException {
        JsonNode root = YAMLMapper.builder().build().readTree(Files.readString(file));
        Map<String, Operation> operations = new LinkedHashMap<>();
        for (JsonNode entry : root.path("operations")) {
            if (entry.isString()) {
                String id = entry.stringValue();
                operations.put(id, new Operation(id, null, false));
                continue;
            }
            String id = entry.path("operationId").stringValue();
            JsonNode permissions = entry.path("permissions");
            JsonNode any = entry.path("anyPermissions");
            List<String> list = null;
            if (permissions.isArray()) {
                list = permissions.valueStream().map(JsonNode::stringValue).toList();
            } else if (any.isArray()) {
                list = any.valueStream().map(JsonNode::stringValue).toList();
            }
            operations.put(id, new Operation(id, list, any.isArray()));
        }
        return new Scope(operations);
    }
}
