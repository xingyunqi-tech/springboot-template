package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/** 与 fastapi-temp {@code tests/parity/test_notifications_parity.py} 一一对应。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsParityIT {

    static final Set<String> VOLATILE_KEYS = Set.of("createdAt", "updatedAt", "readAt", "deletedAt", "sentAt");
    static final String URL = "/api/v1/notifications";

    private final Parity parity = Parity.fromSystemProperties();
    private final AuthParityIT auth = new AuthParityIT();
    private AuthParityIT.TempUser fresh;

    @BeforeAll
    void createUser() {
        fresh = auth.tempUser("notify");
    }

    @AfterAll
    void dropUser() {
        if (fresh != null) {
            ParityDb.dropUser(fresh.email());
        }
    }

    static JsonNode normalize(JsonNode value, @Nullable String key) {
        if (value instanceof ObjectNode object) {
            ObjectNode copy = Parity.JSON.createObjectNode();
            object.properties().forEach(e -> copy.set(e.getKey(), normalize(e.getValue(), e.getKey())));
            return copy;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode copy = Parity.JSON.createArrayNode();
            array.forEach(item -> copy.add(normalize(item, null)));
            return copy;
        }
        if (key != null && VOLATILE_KEYS.contains(key) && !value.isNull()) {
            return StringNode.valueOf("<time>");
        }
        if (("id".equals(key) || "userId".equals(key)) && value.isIntegralNumber()) {
            return StringNode.valueOf("<id>");
        }
        return value;
    }

    static JsonNode assertSame(Parity.Pair pair) {
        String detail = new String(pair.java().body(), StandardCharsets.UTF_8) + "\n"
                + new String(pair.nestjs().body(), StandardCharsets.UTF_8);
        assertThat(pair.java().status()).as(detail).isEqualTo(pair.nestjs().status());
        Parity.assertSameJson(normalize(pair.java().json(), null), normalize(pair.nestjs().json(), null), detail);
        return pair.java().json();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "?page=2&limit=5",
                "?limit=500",
                "?page=0&limit=",
                "?category=ORDER&isRead=false",
                "?page=-1&limit=abc&category=NOPE&isRead=x",
                "?page=1.5"
            })
    void list(String query) {
        assertSame(auth.authed("GET", URL + query, fresh.tokens(), null, Map.of()));
    }

    static Stream<Arguments> singleOperations() {
        return Stream.of(
                Arguments.of("GET", "/unread-count"),
                Arguments.of("GET", "/999999"),
                Arguments.of("GET", "/abc"),
                Arguments.of("GET", "/0"),
                Arguments.of("DELETE", "/999999"),
                Arguments.of("DELETE", "/2147483648"),
                Arguments.of("POST", "/read-all"),
                Arguments.of("POST", "/999999/read"),
                Arguments.of("POST", "/x/read"));
    }

    @ParameterizedTest
    @MethodSource("singleOperations")
    void singleOperations(String method, String path) {
        assertSame(auth.authed(method, URL + path, fresh.tokens(), null, Map.of("Accept-Language", "zh")));
    }

    @Test
    void settingsLifecycle() {
        JsonNode listed = assertSame(auth.authed("GET", URL + "/settings", fresh.tokens(), null, Map.of()));
        List<String> categories = new ArrayList<>();
        listed.path("data").forEach(setting -> categories.add(setting.path("category").asString()));
        assertThat(categories).containsExactlyInAnyOrder("ORDER", "PAYMENT", "SYSTEM", "PROMOTION", "ACTIVITY", "STUDY");
        Map<String, Object> body = AuthParityIT.map(
                "category", "ORDER", "enableInApp", true, "enableEmail", true, "enableSms", true, "enablePush", true);
        Parity.Pair unchanged = auth.authed("PUT", URL + "/settings", fresh.tokens(), body, Map.of());
        assertSame(unchanged);
        for (Parity.Result response : List.of(unchanged.java(), unchanged.nestjs())) {
            JsonNode data = response.json().path("data");
            assertThat(data.path("updatedAt")).isEqualTo(data.path("createdAt"));
        }
        Map<String, Object> smsOff = AuthParityIT.map(
                "category", "ORDER", "enableInApp", true, "enableEmail", true, "enableSms", false, "enablePush", true);
        Parity.Pair changed = auth.authed("PUT", URL + "/settings", fresh.tokens(), smsOff, Map.of());
        assertSame(changed);
        for (Parity.Result response : List.of(changed.java(), changed.nestjs())) {
            JsonNode data = response.json().path("data");
            assertThat(data.path("updatedAt")).isNotEqualTo(data.path("createdAt"));
        }
    }

    static Stream<Map<String, Object>> settingsBodies() {
        return Stream.of(
                AuthParityIT.map(),
                AuthParityIT.map("category", "NOPE", "enableInApp", "yes", "enableEmail", 1),
                AuthParityIT.map("category", "ORDER", "enableInApp", true, "enableEmail", true, "enableSms", true));
    }

    @ParameterizedTest
    @MethodSource("settingsBodies")
    void settingsValidation(Map<String, Object> body) {
        assertSame(auth.authed("PUT", URL + "/settings", fresh.tokens(), body, Map.of()));
    }

    static Stream<Arguments> unauthenticated() {
        return Stream.of(
                Arguments.of("GET", ""),
                Arguments.of("GET", "/unread-count"),
                Arguments.of("PUT", "/settings"),
                Arguments.of("DELETE", "/abc"));
    }

    @ParameterizedTest
    @MethodSource("unauthenticated")
    void unauthenticated(String method, String path) {
        assertSame(parity.request(method, URL + path));
    }

    @Test
    void adminListShape() {
        List<String> tokens = auth.tokens("admin@example.com");
        Parity.Pair pair = auth.authed("GET", URL + "?limit=50", tokens, null, Map.of());
        assertThat(pair.java().status()).isEqualTo(200);
        assertThat(pair.nestjs().status()).isEqualTo(200);
        List<String> keys = List.of(
                "id",
                "userId",
                "type",
                "category",
                "title",
                "content",
                "metadata",
                "channels",
                "sentChannels",
                "isRead",
                "readAt",
                "createdAt",
                "updatedAt",
                "deletedAt");
        for (Parity.Result response : List.of(pair.java(), pair.nestjs())) {
            for (JsonNode item : response.json().path("data").path("list")) {
                List<String> expected = keys.stream()
                        .filter(k -> !(k.equals("metadata") && !item.has("metadata")))
                        .filter(k -> !(k.equals("readAt") && !item.has("readAt")))
                        .toList();
                assertThat(new ArrayList<>(item.propertyNames())).isEqualTo(expected);
            }
        }
    }
}
