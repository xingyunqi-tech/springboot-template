package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** 与 fastapi-temp {@code tests/parity/test_app_profile_parity.py} 一一对应。 */
class AppProfileParityIT {

    static final String URL = "/api/v1/app-profile";
    static final String ADMIN_URL = "/api/v1/admin/app-profile";
    static final String CONFIG_KEY = "app.profile";

    private final Parity parity = Parity.fromSystemProperties();
    private final AuthParityIT auth = new AuthParityIT();

    static JsonNode assertSame(Parity.Pair pair) {
        return ConfigParityIT.assertSame(pair, Set.of(), false);
    }

    static Stream<Map<String, Object>> invalidBodies() {
        return Stream.of(
                AuthParityIT.map("servicePhone", "call me", "serviceEmail", "bad", "appName", "x".repeat(21)),
                AuthParityIT.map("appName", 1, "slogan", List.of("a"), "shareTitle", Map.of("a", 1)),
                AuthParityIT.map("servicePhone", "   -  ", "serviceEmail", "  a@b.co ", "icpLicense", "x".repeat(51)),
                AuthParityIT.map(
                        "copyright", "c".repeat(101), "serviceHours", "h".repeat(41), "companyName", "n".repeat(61)));
    }

    @Test
    void get() {
        assertSame(parity.request("GET", URL));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void updateValidation(Map<String, Object> body) {
        assertSame(auth.authed("PUT", ADMIN_URL, auth.tokens(ConfigParityIT.ADMIN), body, Map.of()));
    }

    @Test
    void updatePermissions() {
        assertSame(auth.authed("PUT", ADMIN_URL, auth.tokens("john.doe@example.com"), Map.of(), Map.of()));
        assertSame(parity.request(
                "PUT", ADMIN_URL, Map.of("Content-Type", "application/json"), AuthParityIT.json(Map.of())));
    }

    @Test
    void updateAndRestore() {
        List<String> tokens = auth.tokens(ConfigParityIT.ADMIN);
        List<Boolean> existed = ConfigParityIT.configExists(CONFIG_KEY);
        Parity.Pair original = parity.request("GET", URL);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("appName", "  备考  ");
            body.put("slogan", "   ");
            body.put("shareTitle", null);
            body.put("servicePhone", " +86 400-123 4567 ");
            body.put("serviceEmail", "help@example.com");
            body.put("unknown", "x");
            assertSame(auth.authed("PUT", ADMIN_URL, tokens, body, Map.of()));
            assertSame(parity.request("GET", URL));
            assertSame(auth.authed("PUT", ADMIN_URL, tokens, AuthParityIT.map("slogan", "新"), Map.of()));
            assertSame(parity.request("GET", URL));
        } finally {
            List<Parity.Result> results = List.of(original.java(), original.nestjs());
            List<String> sides = List.of("java", "nestjs");
            for (int i = 0; i < 2; i++) {
                Parity.Result result = parity.sendTo(
                        sides.get(i),
                        "PUT",
                        ADMIN_URL,
                        Map.of("Authorization", "Bearer " + tokens.get(i), "Content-Type", "application/json"),
                        AuthParityIT.json(results.get(i).json().path("data")));
                assertThat(result.status()).isEqualTo(200);
            }
            ConfigParityIT.deleteConfigs(List.of(!existed.get(0), !existed.get(1)), CONFIG_KEY);
        }
    }
}
