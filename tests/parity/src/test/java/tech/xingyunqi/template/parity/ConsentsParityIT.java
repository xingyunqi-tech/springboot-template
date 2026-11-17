package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 与 fastapi-temp {@code tests/parity/test_consents_parity.py} 一一对应。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsentsParityIT {

    static final String URL = "/api/v1/me/consents";

    private final Parity parity = Parity.fromSystemProperties();
    private final AuthParityIT auth = new AuthParityIT();
    private AuthParityIT.TempUser fresh;

    @BeforeAll
    void createUser() {
        fresh = auth.tempUser("consent");
    }

    @AfterAll
    void dropUser() {
        if (fresh != null) {
            ParityDb.dropUser(fresh.email());
        }
    }

    static void assertSame(Parity.Pair pair) {
        ConfigParityIT.assertSame(pair, Set.of("agreedAt"), false);
    }

    @Test
    void status() {
        assertSame(auth.authed("GET", URL, fresh.tokens(), null, Map.of()));
    }

    static Stream<Arguments> agreeCases() {
        List<Map<String, Object>> bodies = List.of(
                AuthParityIT.map(),
                AuthParityIT.map("type", "PRIVACY_POLICY", "version", "2026-10-01"),
                AuthParityIT.map("type", "AI_SERVICE", "version", "v".repeat(51)),
                AuthParityIT.map("type", "NOPE", "version", ""),
                AuthParityIT.map("type", 1, "version", 2),
                AuthParityIT.map("type", "USER_AGREEMENT", "version", "  "));
        List<Arguments> cases = new ArrayList<>();
        for (String lang : List.of("en", "zh")) {
            for (Map<String, Object> body : bodies) {
                cases.add(Arguments.of(body, lang));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("agreeCases")
    void agree(Map<String, Object> body, String lang) {
        assertSame(auth.authed("POST", URL, fresh.tokens(), body, Map.of("Accept-Language", lang)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void unauthenticated(String method) {
        assertSame(
                parity.request(method, URL, Map.of("Content-Type", "application/json"), AuthParityIT.json(Map.of())));
    }

    @Test
    void configuredVersion() {
        String key = "consent.privacyPolicyVersion";
        String version = "parity-" + ConfigParityIT.RUN;
        List<String> admin = auth.tokens(ConfigParityIT.ADMIN);
        List<Boolean> existed = ConfigParityIT.configExists(key);
        Parity.Pair original =
                auth.authed("GET", ConfigParityIT.URL + "/admin/" + key, admin, null, Map.of());
        AuthParityIT.TempUser user = auth.tempUser("consent-current");
        try {
            Parity.Pair configured = auth.authed(
                    "PUT",
                    ConfigParityIT.URL + "/" + key,
                    admin,
                    AuthParityIT.map("value", AuthParityIT.map("value", version)),
                    Map.of());
            assertThat(configured.java().status()).isEqualTo(200);
            assertThat(configured.nestjs().status()).isEqualTo(200);

            assertSame(auth.authed("GET", URL, user.tokens(), null, Map.of()));
            assertSame(auth.authed(
                    "POST", URL, user.tokens(), AuthParityIT.map("type", "PRIVACY_POLICY", "version", "old"), Map.of()));
            Parity.Pair first = auth.authed(
                    "POST", URL, user.tokens(), AuthParityIT.map("type", "PRIVACY_POLICY", "version", version), Map.of());
            assertSame(first);
            assertSame(auth.authed("GET", URL, user.tokens(), null, Map.of()));
            Parity.Pair again = auth.authed(
                    "POST", URL, user.tokens(), AuthParityIT.map("type", "PRIVACY_POLICY", "version", version), Map.of());
            assertSame(again);
            assertThat(again.java().json().path("data").path("agreedAt"))
                    .isEqualTo(first.java().json().path("data").path("agreedAt"));
            assertThat(again.nestjs().json().path("data").path("agreedAt"))
                    .isEqualTo(first.nestjs().json().path("data").path("agreedAt"));
        } finally {
            ParityDb.dropUser(user.email());
            restore(key, admin, original, existed);
        }
    }

    /** 两侧各自写回测试前的配置值；测试前不存在的一侧写入占位值使缓存失效后删除该行。 */
    void restore(String key, List<String> tokens, Parity.Pair original, List<Boolean> existed) {
        List<Parity.Result> results = List.of(original.java(), original.nestjs());
        List<String> sides = List.of("java", "nestjs");
        for (int i = 0; i < 2; i++) {
            Object value = existed.get(i)
                    ? results.get(i).json().path("data").path("value")
                    : AuthParityIT.map("value", "");
            Parity.Result result = parity.sendTo(
                    sides.get(i),
                    "PUT",
                    ConfigParityIT.URL + "/" + key,
                    Map.of("Authorization", "Bearer " + tokens.get(i), "Content-Type", "application/json"),
                    AuthParityIT.json(AuthParityIT.map("value", value)));
            assertThat(result.status()).isEqualTo(200);
        }
        ConfigParityIT.deleteConfigs(List.of(!existed.get(0), !existed.get(1)), key);
    }
}
