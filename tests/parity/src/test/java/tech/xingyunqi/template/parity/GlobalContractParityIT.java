package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** 与 fastapi-temp {@code tests/parity/test_global_contract.py} 一一对应。 */
class GlobalContractParityIT {

    private static final List<String> CONTRACT_HEADERS = List.of("content-type");
    private static final List<String> SECURITY_HEADERS = List.of(
            "content-security-policy",
            "cross-origin-resource-policy",
            "origin-agent-cluster",
            "referrer-policy",
            "strict-transport-security",
            "x-content-type-options",
            "x-dns-prefetch-control",
            "x-download-options",
            "x-frame-options",
            "x-permitted-cross-domain-policies",
            "x-xss-protection");
    private static final List<String> CORS_HEADERS = List.of(
            "access-control-allow-origin",
            "access-control-allow-credentials",
            "access-control-expose-headers",
            "access-control-allow-methods",
            "access-control-allow-headers",
            "vary");

    private final Parity parity = Parity.fromSystemProperties();

    private JsonNode same(String method, String url) {
        Parity.Pair pair = parity.request(method, url);
        assertThat(pair.java().status()).isEqualTo(pair.nestjs().status());
        for (String header : CONTRACT_HEADERS) {
            assertThat(pair.java().header(header))
                    .as(header)
                    .isEqualTo(pair.nestjs().header(header));
        }
        assertThat(pair.java().json()).isEqualTo(pair.nestjs().json());
        return pair.java().json();
    }

    static Stream<Arguments> unmatchedRoutes() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/nope"),
                Arguments.of("GET", "/api/v1/nope?a=1%20b&c&d=%E4%B8%AD"),
                Arguments.of("DELETE", "/api/nope/x"),
                Arguments.of("PATCH", "/api/health?x=1"),
                Arguments.of("POST", "/"),
                Arguments.of("PUT", "/metrics/"));
    }

    @ParameterizedTest(name = "test_unmatched_routes {0} {1}")
    @MethodSource
    void unmatchedRoutes(String method, String url) {
        Parity.Pair pair = parity.request(method, url);
        assertThat(pair.java().status()).isEqualTo(404);
        assertThat(pair.nestjs().status()).isEqualTo(404);
        assertThat(pair.java().header("allow")).isNull();
        assertThat(pair.java().json()).isEqualTo(pair.nestjs().json());
    }

    @ParameterizedTest(name = "test_matched_routes {0}")
    @ValueSource(strings = {"/", "/api/health", "/API/HEALTH", "/api/health/", "/Api/Health/"})
    void matchedRoutes(String url) {
        Parity.Pair pair = parity.request("GET", url);
        assertThat(pair.java().status()).isEqualTo(200);
        assertThat(pair.nestjs().status()).isEqualTo(200);
        assertThat(pair.java().header("content-type")).isEqualTo(pair.nestjs().header("content-type"));
        assertThat(fieldNames(pair.java().json()))
                .isEqualTo(fieldNames(pair.nestjs().json()));
        assertThat(fieldNames(pair.java().json())).containsExactlyInAnyOrder("code", "message", "data");
    }

    @Test
    @DisplayName("test_app_info")
    void appInfo() {
        same("GET", "/");
    }

    @Test
    @DisplayName("test_health_shape")
    void healthShape() {
        Parity.Pair pair = parity.request("GET", "/api/health");
        JsonNode java = pair.java().json().get("data");
        JsonNode nestjs = pair.nestjs().json().get("data");
        assertThat(fieldNames(java)).isEqualTo(fieldNames(nestjs));
        assertThat(fieldNames(java.get("memory"))).isEqualTo(fieldNames(nestjs.get("memory")));
        assertThat(java.get("status").stringValue())
                .isEqualTo("ok")
                .isEqualTo(nestjs.get("status").stringValue());
        assertThat(java.get("timestamp").stringValue())
                .hasSameSizeAs(nestjs.get("timestamp").stringValue())
                .endsWith("Z");
    }

    @Test
    @DisplayName("test_head")
    void head() {
        Parity.Pair pair = parity.request("HEAD", "/api/health");
        assertThat(pair.java().status()).isEqualTo(200);
        assertThat(pair.nestjs().status()).isEqualTo(200);
        assertThat(pair.java().body()).isEmpty();
        assertThat(pair.nestjs().body()).isEmpty();
    }

    @ParameterizedTest(name = "test_request_id_echoed {0}")
    @MethodSource
    void requestIdEchoed(String requestId) {
        Parity.Pair pair = parity.request("GET", "/", Map.of("X-Request-Id", requestId));
        assertThat(pair.java().header("x-request-id")).isEqualTo(requestId);
        assertThat(pair.nestjs().header("x-request-id")).isEqualTo(requestId);
    }

    static Stream<String> requestIdEchoed() {
        return Stream.of("abc.1:2-3", "x".repeat(128));
    }

    @ParameterizedTest(name = "test_request_id_generated {0}")
    @MethodSource
    void requestIdGenerated(String requestId) {
        Map<String, String> headers = requestId.isEmpty() ? Map.of() : Map.of("X-Request-Id", requestId);
        Parity.Pair pair = parity.request("GET", "/api/v1/nope", headers);
        for (Parity.Result result : List.of(pair.java(), pair.nestjs())) {
            String generated = result.header("x-request-id");
            assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        }
    }

    static Stream<String> requestIdGenerated() {
        return Stream.of("", "bad id!", "x".repeat(129));
    }

    @Test
    @DisplayName("test_security_headers")
    void securityHeaders() {
        Parity.Pair pair = parity.request("GET", "/api/health");
        for (String header : SECURITY_HEADERS) {
            assertThat(pair.java().header(header))
                    .as(header)
                    .isEqualTo(pair.nestjs().header(header));
        }
    }

    @ParameterizedTest(name = "test_cors_simple_request {0}")
    @ValueSource(strings = {"", "http://a.example"})
    void corsSimpleRequest(String origin) {
        Map<String, String> headers = origin.isEmpty() ? Map.of() : Map.of("Origin", origin);
        Parity.Pair pair = parity.request("GET", "/", headers);
        for (String header : CORS_HEADERS) {
            assertThat(pair.java().header(header))
                    .as(header)
                    .isEqualTo(pair.nestjs().header(header));
        }
    }

    static Stream<Map<String, String>> corsPreflight() {
        return Stream.of(
                Map.of(),
                Map.of("Origin", "http://a.example", "Access-Control-Request-Method", "POST"),
                Map.of(
                        "Origin", "http://a.example",
                        "Access-Control-Request-Method", "PUT",
                        "Access-Control-Request-Headers", "authorization,content-type,x-foo"));
    }

    @ParameterizedTest(name = "test_cors_preflight {0}")
    @MethodSource
    void corsPreflight(Map<String, String> headers) {
        Parity.Pair pair = parity.request("OPTIONS", "/api/v1/auth/me", headers);
        assertThat(pair.java().status()).isEqualTo(204);
        assertThat(pair.nestjs().status()).isEqualTo(204);
        assertThat(pair.java().body()).isEmpty();
        assertThat(pair.nestjs().body()).isEmpty();
        for (String header : CORS_HEADERS) {
            assertThat(pair.java().header(header))
                    .as(header)
                    .isEqualTo(pair.nestjs().header(header));
        }
    }

    static Stream<Arguments> invalidJsonRejectedBeforeRouting() {
        return Stream.of(
                Arguments.of("POST", "/api/v1/nope", "x"),
                Arguments.of("GET", "/", "{bad"),
                Arguments.of("PATCH", "/api/health", "null"),
                Arguments.of("POST", "/api/v1/nope", "   "));
    }

    @ParameterizedTest(name = "test_invalid_json_rejected_before_routing {0} {1}")
    @MethodSource
    void invalidJsonRejectedBeforeRouting(String method, String url, String content) {
        Parity.Pair pair = parity.request(
                method, url, Map.of("content-type", "application/json"), content.getBytes(StandardCharsets.UTF_8));
        assertThat(pair.java().status()).isEqualTo(400);
        assertThat(pair.nestjs().status()).isEqualTo(400);
        assertThat(pair.java().json().get("code"))
                .isEqualTo(pair.nestjs().json().get("code"));
        assertThat(pair.java().json().get("data").isNull()).isTrue();
        assertThat(pair.nestjs().json().get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("test_oversized_body")
    void oversizedBody() {
        String content = "[" + "1,".repeat(140000) + "1]";
        Parity.Pair pair = parity.request(
                "POST",
                "/api/v1/nope",
                Map.of("content-type", "application/json"),
                content.getBytes(StandardCharsets.UTF_8));
        assertThat(pair.java().status()).isEqualTo(413);
        assertThat(pair.nestjs().status()).isEqualTo(413);
        assertThat(pair.java().json()).isEqualTo(pair.nestjs().json());
    }

    @Test
    @DisplayName("test_percent_encoded_route_segment")
    void percentEncodedRouteSegment() {
        same("GET", "/api/%68ealth");
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.propertyNames().stream().sorted().toList();
    }
}
