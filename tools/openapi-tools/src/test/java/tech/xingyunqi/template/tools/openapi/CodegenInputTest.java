package tech.xingyunqi.template.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

@Tag("contract")
class CodegenInputTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String BASELINE = """
            {"openapi":"3.0.0","paths":{
              "/a":{"get":{"operationId":"getA","responses":{"200":{"description":"","content":{"application/json":{"schema":{"$ref":"#/components/schemas/A"}}}}}},
                    "post":{"operationId":"postA","requestBody":{"content":{"multipart/form-data":{"schema":{"type":"object"}}}},"responses":{"200":{"description":""}}}},
              "/b":{"get":{"operationId":"getB","responses":{"200":{"description":"","content":{"application/json":{"schema":{"$ref":"#/components/schemas/B"}}}}}}}},
             "components":{"schemas":{
               "A":{"type":"object","required":["x","y"],"properties":{"x":{"type":"string"},"y":{"$ref":"#/components/schemas/C"}}},
               "B":{"type":"object"},
               "C":{"type":"object"}}}}
            """;

    private static Scope scope(String... ids) {
        Map<String, Scope.Operation> operations = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            operations.put(id, new Scope.Operation(id, null, false));
        }
        return new Scope(operations);
    }

    private static ObjectNode build(Scope scope, String overlay) {
        return CodegenInput.build(
                (ObjectNode) JSON.readTree(BASELINE),
                scope,
                YAMLMapper.builder().build().readTree(overlay));
    }

    @Test
    @DisplayName("只保留 scope 内的 operation 及其传递引用的 schema")
    void keepsScopedOperationsAndReachableSchemas() {
        ObjectNode result = build(scope("getA"), "{}");
        assertThat(result.path("paths").propertyNames()).containsExactly("/a");
        assertThat(result.path("paths").path("/a").propertyNames()).containsExactly("get");
        assertThat(result.path("components").path("schemas").propertyNames()).containsExactlyInAnyOrder("A", "C");
    }

    @Test
    @DisplayName("overlay 修正字段可空与非必填，并补充响应 schema")
    void appliesOverlay() {
        ObjectNode result = build(scope("getA", "postA"), """
                schemas:
                  A:
                    nullable: [x]
                    optional: [y]
                operations:
                  postA:
                    responses:
                      '200': {type: object, properties: {ok: {type: boolean}}}
                """);
        JsonNode a = result.path("components").path("schemas").path("A");
        assertThat(a.path("properties").path("x").path("nullable").booleanValue())
                .isTrue();
        assertThat(a.path("required").valueStream().map(JsonNode::stringValue).toList())
                .isEqualTo(List.of("x"));
        assertThat(result.at("/paths/~1a/post/responses/200/content/application~1json/schema/type")
                        .stringValue())
                .isEqualTo("object");
    }

    @Test
    @DisplayName("overlay 中 requestBody 为 null 时去掉该 operation 的请求体")
    void removesRequestBody() {
        ObjectNode result = build(scope("postA"), """
                operations:
                  postA:
                    requestBody: null
                """);
        assertThat(result.at("/paths/~1a/post").has("requestBody")).isFalse();
        assertThat(build(scope("postA"), "{}").at("/paths/~1a/post").has("requestBody"))
                .isTrue();
    }

    @Test
    @DisplayName("scope 中不存在于基线的 operationId 报错")
    void rejectsUnknownOperation() {
        assertThatThrownBy(() -> build(scope("getA", "nope"), "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("overlay 引用不存在的字段报错")
    void rejectsUnknownOverlayField() {
        assertThatThrownBy(() -> build(scope("getA"), "schemas: {A: {nullable: [z]}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("A.z");
    }
}
