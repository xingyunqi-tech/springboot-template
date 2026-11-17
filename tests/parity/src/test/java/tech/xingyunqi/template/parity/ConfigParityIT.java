package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
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

/** 与 fastapi-temp {@code tests/parity/test_config_parity.py} 一一对应。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigParityIT {

    static final String URL = "/api/v1/config";
    static final String RUN = String.valueOf(System.currentTimeMillis());
    static final String ADMIN = "admin@example.com";

    private final Parity parity = Parity.fromSystemProperties();
    private final AuthParityIT auth = new AuthParityIT();

    /** 将 {@code keys} 中非 null 的值替换为占位符，{@code ids} 为 true 时整数 id 也替换。 */
    static JsonNode normalize(JsonNode value, String key, Set<String> keys, boolean ids) {
        if (value instanceof ObjectNode object) {
            ObjectNode copy = Parity.JSON.createObjectNode();
            object.properties().forEach(e -> copy.set(e.getKey(), normalize(e.getValue(), e.getKey(), keys, ids)));
            return copy;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode copy = Parity.JSON.createArrayNode();
            array.forEach(item -> copy.add(normalize(item, null, keys, ids)));
            return copy;
        }
        if (key != null && keys.contains(key) && !value.isNull()) {
            return StringNode.valueOf("<time>");
        }
        if (ids && "id".equals(key) && value.isIntegralNumber()) {
            return StringNode.valueOf("<id>");
        }
        return value;
    }

    static JsonNode assertSame(Parity.Pair pair, Set<String> keys, boolean ids) {
        String detail = new String(pair.java().body(), StandardCharsets.UTF_8) + "\n"
                + new String(pair.nestjs().body(), StandardCharsets.UTF_8);
        assertThat(pair.java().status()).as(detail).isEqualTo(pair.nestjs().status());
        assertThat(pair.java().header("content-type")).isEqualTo(pair.nestjs().header("content-type"));
        Parity.assertSameJson(
                normalize(pair.java().json(), null, keys, ids), normalize(pair.nestjs().json(), null, keys, ids), detail);
        return pair.java().json();
    }

    static JsonNode assertSame(Parity.Pair pair) {
        return assertSame(pair, Set.of("createdAt", "updatedAt"), false);
    }

    /** (java, nestjs) 两侧库中是否存在该配置行。 */
    static List<Boolean> configExists(String key) {
        return ParityDb.both("SELECT count(*) FROM config WHERE key = '" + key + "'").stream()
                .map(count -> !"0".equals(count))
                .toList();
    }

    /** 按 {@code sides}（java, nestjs）从两侧库删除配置行。 */
    static void deleteConfigs(List<Boolean> sides, String... keys) {
        String listed = Stream.of(keys).map(key -> "'" + key + "'").collect(Collectors.joining(", "));
        String sql = "DELETE FROM config WHERE key IN (" + listed + ")";
        if (sides.get(0)) {
            ParityDb.psql(ParityDb.JAVA, sql);
        }
        if (sides.get(1)) {
            ParityDb.psql(ParityDb.NESTJS, sql);
        }
    }

    @AfterAll
    void dropRunConfigs() {
        deleteConfigs(List.of(true, true), "parity." + RUN, "parity.lifecycle." + RUN);
    }

    Parity.Pair put(String path, List<String> tokens, Object body) {
        return auth.authed("PUT", path, tokens, body, Map.of());
    }

    static Stream<Arguments> publicGetCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String lang : List.of("en", "zh")) {
            for (String key : List.of(
                    "ai.enabled",
                    "ai.quota.chat",
                    "practice.trialSize",
                    "ai.ratePerMinute",
                    "ai.model",
                    "app.profile",
                    "consent.privacyPolicyVersion",
                    "unknown.key",
                    "%E4%B8%AD")) {
                cases.add(Arguments.of(key, lang));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("publicGetCases")
    void publicGet(String key, String lang) {
        assertSame(parity.request("GET", URL + "/" + key, Map.of("Accept-Language", lang)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ai.ratePerMinute", "ai.enabled", "practice.dailySetSize", "ai.model", "unknown.key"})
    void adminGet(String key) {
        assertSame(auth.authed("GET", URL + "/admin/" + key, auth.tokens(ADMIN), null, Map.of()));
    }

    @Test
    void requiresPermission() {
        List<String> john = auth.tokens("john.doe@example.com");
        assertSame(auth.authed("GET", URL + "/admin/ai.enabled", john, null, Map.of()));
        assertSame(put(URL + "/parity.denied", john, AuthParityIT.map("value", Map.of())));
        assertSame(parity.request("GET", URL + "/admin/ai.enabled"));
        assertSame(parity.request(
                "PUT",
                URL + "/parity.denied",
                Map.of("Content-Type", "application/json"),
                AuthParityIT.json(AuthParityIT.map("value", Map.of()))));
    }

    static Stream<Arguments> upsertErrorCases() {
        Map<String, Object> nullPublic = new LinkedHashMap<>();
        nullPublic.put("value", List.of(1));
        nullPublic.put("isPublic", null);
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("value", null);
        return Stream.of(
                Arguments.of("app.profile", AuthParityIT.map("value", Map.of())),
                Arguments.of("parity." + RUN, AuthParityIT.map()),
                Arguments.of("parity." + RUN, AuthParityIT.map("value", "x", "isPublic", "yes")),
                Arguments.of("parity." + RUN, nullPublic),
                Arguments.of("parity." + RUN, nullValue));
    }

    @ParameterizedTest
    @MethodSource("upsertErrorCases")
    void upsertErrors(String key, Map<String, Object> body) {
        assertSame(put(URL + "/" + key, auth.tokens(ADMIN), body));
    }

    @Test
    void upsertLifecycle() {
        List<String> tokens = auth.tokens(ADMIN);
        String key = URL + "/parity.lifecycle." + RUN;
        String adminKey = URL + "/admin/parity.lifecycle." + RUN;
        assertSame(parity.request("GET", key));
        JsonNode created = assertSame(put(
                key,
                tokens,
                AuthParityIT.map("value", AuthParityIT.map("b", 1, "a", AuthParityIT.map("y", 2, "x", 1)))));
        assertThat(created.path("data").path("isPublic").asBoolean()).isFalse();
        assertSame(parity.request("GET", key));
        assertSame(auth.authed("GET", adminKey, tokens, null, Map.of()));

        Parity.Pair same = put(
                key,
                tokens,
                AuthParityIT.map("value", AuthParityIT.map("a", AuthParityIT.map("x", 1, "y", 2), "b", 1)));
        assertSame(same);
        for (Parity.Result result : List.of(same.java(), same.nestjs())) {
            JsonNode data = result.json().path("data");
            assertThat(data.path("updatedAt")).isEqualTo(data.path("createdAt"));
        }

        assertSame(put(key, tokens, AuthParityIT.map("value", AuthParityIT.map("c", true), "isPublic", true)));
        assertSame(parity.request("GET", key));
        Parity.Pair kept = put(key, tokens, AuthParityIT.map("value", AuthParityIT.map("c", false)));
        assertThat(kept.java().json().path("data").path("isPublic").asBoolean()).isTrue();
        assertSame(kept);
        assertSame(parity.request("GET", key));
    }

    @Test
    void upsertRegisteredKeyRestoresOriginal() {
        List<String> tokens = auth.tokens(ADMIN);
        String key = "ai.ratePerMinute";
        List<Boolean> existed = configExists(key);
        Parity.Pair original = auth.authed("GET", URL + "/admin/" + key, tokens, null, Map.of());
        try {
            assertSame(put(
                    URL + "/" + key,
                    tokens,
                    AuthParityIT.map("value", AuthParityIT.map("value", 11), "isPublic", true)));
            assertSame(parity.request("GET", URL + "/" + key));
            assertSame(auth.authed("GET", URL + "/admin/" + key, tokens, null, Map.of()));
        } finally {
            restore(key, tokens, original);
            deleteConfigs(List.of(!existed.get(0), !existed.get(1)), key);
        }
    }

    /** 两侧各自写回测试前的配置值。 */
    void restore(String key, List<String> tokens, Parity.Pair original) {
        List<Parity.Result> results = List.of(original.java(), original.nestjs());
        List<String> sides = List.of("java", "nestjs");
        for (int i = 0; i < 2; i++) {
            Parity.Result result = parity.sendTo(
                    sides.get(i),
                    "PUT",
                    URL + "/" + key,
                    Map.of("Authorization", "Bearer " + tokens.get(i), "Content-Type", "application/json"),
                    AuthParityIT.json(AuthParityIT.map(
                            "value", results.get(i).json().path("data").path("value"))));
            assertThat(result.status()).isEqualTo(200);
        }
    }
}
