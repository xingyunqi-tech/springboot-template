package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * 与 fastapi-temp {@code tests/parity/test_auth_parity.py} 一一对应；限流用例（{@code test_throttled_response}）与账号清理重投递
 * （{@code test_admin_retrigger_cleanup_errors}）随 M3 移植。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthParityIT {

    static final Set<String> VOLATILE_KEYS =
            Set.of("token", "refreshToken", "tokenExpires", "createdAt", "updatedAt", "lastActiveAt", "ip");
    static final String RUN = String.valueOf(System.currentTimeMillis());
    static final String PASSWORD = md5("Secret00");

    private final Parity parity = Parity.fromSystemProperties();
    private TempUser profileUser;

    /** 两侧各自注册的同一邮箱用户；{@code ids} 与 {@code tokens} 为 (java, nestjs)。 */
    record TempUser(String email, List<Integer> ids, List<String> tokens) {}

    static String md5(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] json(Object body) {
        return Parity.JSON.writeValueAsBytes(body);
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    static JsonNode normalize(JsonNode value, String key) {
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
            return StringNode.valueOf("<" + (value.isNumber() ? "int" : value.isString() ? "str" : "other") + ">");
        }
        if ("id".equals(key) && value.isIntegralNumber()) {
            return StringNode.valueOf("<id>");
        }
        return value;
    }

    static JsonNode assertSame(Parity.Pair pair) {
        Parity.Result java = pair.java();
        Parity.Result nestjs = pair.nestjs();
        for (Parity.Result result : List.of(java, nestjs)) {
            assertThat(result.status()).as("unexpected 429").isNotEqualTo(429);
        }
        String detail = new String(java.body(), StandardCharsets.UTF_8) + "\n"
                + new String(nestjs.body(), StandardCharsets.UTF_8);
        assertThat(java.status()).as(detail).isEqualTo(nestjs.status());
        assertThat(java.header("content-type")).isEqualTo(nestjs.header("content-type"));
        assertThat(normalize(java.json(), null)).as(detail).isEqualTo(normalize(nestjs.json(), null));
        return java.json();
    }

    Parity.Pair post(String path, Object body, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("Content-Type", "application/json");
        return parity.request("POST", path, all, json(body));
    }

    List<String> tokens(String email) {
        Parity.Pair pair = post("/api/v1/auth/email/login", map("email", email, "password", PASSWORD), Map.of());
        return List.of(
                pair.java().json().path("data").path("token").asString(),
                pair.nestjs().json().path("data").path("token").asString());
    }

    Parity.Pair authed(String method, String path, List<String> tokens, Object body, Map<String, String> extra) {
        return authedEach(method, path, path, tokens, body, extra);
    }

    Parity.Pair authedEach(
            String method, String javaPath, String nestjsPath, List<String> tokens, Object body, Map<String, String> extra) {
        Map<String, String> javaHeaders = new LinkedHashMap<>(extra);
        Map<String, String> nestjsHeaders = new LinkedHashMap<>(extra);
        javaHeaders.put("Authorization", "Bearer " + tokens.get(0));
        nestjsHeaders.put("Authorization", "Bearer " + tokens.get(1));
        byte[] payload = new byte[0];
        if (body != null) {
            payload = json(body);
            javaHeaders.put("Content-Type", "application/json");
            nestjsHeaders.put("Content-Type", "application/json");
        }
        return parity.requestEach(method, javaPath, javaHeaders, nestjsPath, nestjsHeaders, payload);
    }

    TempUser tempUser(String prefix) {
        String email = "parity." + prefix + "." + System.currentTimeMillis() + "@example.com";
        Parity.Pair registered = post(
                "/api/v1/auth/email/register",
                map("email", email, "password", PASSWORD, "firstName", "P", "lastName", "T"),
                Map.of());
        assertThat(registered.java().status()).isEqualTo(200);
        assertThat(registered.nestjs().status()).isEqualTo(200);
        List<Integer> ids = ParityDb.both("SELECT id FROM \"user\" WHERE email = '" + email + "'").stream()
                .map(Integer::valueOf)
                .toList();
        return new TempUser(email, ids, tokens(email));
    }

    @BeforeAll
    void createProfileUser() {
        profileUser = tempUser("profile");
    }

    @AfterAll
    void dropProfileUser() {
        if (profileUser != null) {
            ParityDb.dropUser(profileUser.email());
        }
    }

    static Stream<Arguments> loginCases() {
        List<Object> bodies = List.of(
                map(),
                map("email", "admin@example.com", "password", PASSWORD),
                map("email", " ADMIN@Example.com ", "password", PASSWORD.toUpperCase(java.util.Locale.ROOT)),
                map("email", "missing@example.com", "password", PASSWORD),
                map("email", "admin@example.com", "password", md5("wrong")),
                map("email", "not-an-email", "password", "short"),
                map("email", 123, "password", null),
                map("email", "admin@example.com", "password", PASSWORD, "extra", true));
        List<Arguments> cases = new ArrayList<>();
        for (String lang : List.of("en", "zh-CN")) {
            for (Object body : bodies) {
                cases.add(Arguments.of(body, lang));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("loginCases")
    void emailLogin(Object body, String lang) {
        assertSame(post("/api/v1/auth/email/login", body, Map.of("Accept-Language", lang)));
    }

    static Stream<Object> registerCases() {
        return Stream.of(
                map(),
                map("email", "john.doe@example.com", "password", PASSWORD, "firstName", "J", "lastName", "D"),
                map("email", "parity." + RUN + "@example.com", "password", PASSWORD, "firstName", "<b>P</b>",
                        "lastName", "R"),
                map("email", "x@example.com", "password", PASSWORD, "firstName", "", "lastName", 1));
    }

    @ParameterizedTest
    @MethodSource("registerCases")
    void emailRegister(Object body) {
        assertSame(post("/api/v1/auth/email/register", body, Map.of()));
    }

    @AfterAll
    void dropRegistered() {
        ParityDb.dropUser("parity." + RUN + "@example.com");
    }

    static Stream<Arguments> linkErrorCases() {
        return Stream.of(
                Arguments.of("/api/v1/auth/email/confirm", map("hash", "invalid")),
                Arguments.of("/api/v1/auth/email/confirm", map()),
                Arguments.of("/api/v1/auth/email/confirm/new", map("hash", "a.b.c")),
                Arguments.of("/api/v1/auth/forgot/password", map("email", "missing@example.com")),
                Arguments.of("/api/v1/auth/forgot/password", map("email", "bad")),
                Arguments.of("/api/v1/auth/reset/password", map("hash", "invalid", "password", PASSWORD)),
                Arguments.of("/api/v1/auth/reset/password", map("hash", "x", "password", "plain")));
    }

    @ParameterizedTest
    @MethodSource("linkErrorCases")
    void emailLinkErrors(String url, Object body) {
        assertSame(post(url, body, Map.of()));
    }

    static Stream<Arguments> unauthenticatedCases() {
        List<Map<String, String>> headers = List.of(
                Map.of(),
                Map.of("Authorization", "Bearer nope"),
                Map.of("Authorization", "Basic abc"),
                Map.of("Authorization", "Bearer"));
        List<List<String>> routes = List.of(
                List.of("GET", "/api/v1/auth/me"),
                List.of("POST", "/api/v1/auth/refresh"),
                List.of("GET", "/api/v1/auth/sessions"),
                List.of("POST", "/api/v1/auth/logout"),
                List.of("GET", "/api/v1/users"),
                List.of("DELETE", "/api/v1/users/abc"));
        List<Arguments> cases = new ArrayList<>();
        for (Map<String, String> header : headers) {
            for (List<String> route : routes) {
                cases.add(Arguments.of(route.get(0), route.get(1), header));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("unauthenticatedCases")
    void unauthenticated(String method, String url, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("Accept-Language", "zh");
        assertSame(parity.request(method, url, all));
    }

    @Test
    void meAndSessions() {
        List<String> tokens = tokens("admin@example.com");
        assertSame(authed("GET", "/api/v1/auth/me", tokens, null, Map.of()));
        Parity.Pair sessions = authed("GET", "/api/v1/auth/sessions", tokens, null, Map.of());
        assertThat(sessions.java().status()).isEqualTo(200);
        assertThat(sessions.nestjs().status()).isEqualTo(200);
        assertThat(Set.copyOf(sessions.java().json().path("data").get(0).propertyNames()))
                .isEqualTo(Set.copyOf(sessions.nestjs().json().path("data").get(0).propertyNames()));
    }

    static Stream<Object> updateMeErrors() {
        return Stream.of(
                map("firstName", 123, "password", md5("x")),
                map("password", md5("x"), "oldPassword", md5("wrong")),
                map("email", "john.doe@example.com"),
                map("gender", "male", "birthday", "1990/01/01"),
                map("photo", map("id", "not-uuid")),
                map("photo", map("id", "3f0c8a52-7d0e-4b59-9f8e-2a1d6c4b7e10")));
    }

    @ParameterizedTest
    @MethodSource("updateMeErrors")
    void updateMeErrors(Object body) {
        assertSame(authed("PUT", "/api/v1/auth/me", profileUser.tokens(), body, Map.of()));
    }

    static Stream<Object> changePasswordErrors() {
        return Stream.of(
                map(),
                map("oldPassword", md5("wrong"), "newPassword", PASSWORD),
                map("oldPassword", "x", "newPassword", "y"));
    }

    @ParameterizedTest
    @MethodSource("changePasswordErrors")
    void changePasswordErrors(Object body) {
        assertSame(authed("PUT", "/api/v1/auth/password", profileUser.tokens(), body, Map.of()));
    }

    @Test
    void refreshRotation() {
        Parity.Pair login = post(
                "/api/v1/auth/email/login", map("email", "john.doe@example.com", "password", PASSWORD), Map.of());
        List<String> refresh = List.of(
                login.java().json().path("data").path("refreshToken").asString(),
                login.nestjs().json().path("data").path("refreshToken").asString());
        assertSame(authed("POST", "/api/v1/auth/refresh", refresh, null, Map.of()));
        assertSame(authed("POST", "/api/v1/auth/refresh", refresh, null, Map.of()));
    }

    @Test
    void revokeUnknownSession() {
        List<String> tokens = tokens("john.doe@example.com");
        assertSame(authed("DELETE", "/api/v1/auth/sessions/999999", tokens, null, Map.of()));
        assertSame(authed("DELETE", "/api/v1/auth/sessions/0", tokens, null, Map.of()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/users?limit=2",
                "/api/v1/users?page=1&limit=1&sort=%5B%7B%22orderBy%22%3A%22id%22%2C%22order%22%3A%22desc%22%7D%5D",
                "/api/v1/users?limit=0",
                "/api/v1/users?page=abc",
                "/api/v1/users?filters=%7B%22roles%22%3A%5B%7B%22id%22%3A%22x%22%7D%5D%7D",
                "/api/v1/users?filters=notjson",
                "/api/v1/users/1",
                "/api/v1/users/999999",
                "/api/v1/users/2147483648"
            })
    void adminReads(String url) {
        Parity.Pair pair = authed("GET", url, tokens("admin@example.com"), null, Map.of());
        if (url.contains("sort=") || !url.contains("/users?")) {
            assertSame(pair);
            return;
        }
        assertThat(pair.java().status()).isEqualTo(pair.nestjs().status());
        JsonNode java = normalize(pair.java().json(), null);
        JsonNode nestjs = normalize(pair.nestjs().json(), null);
        if (java.path("data").path("data").isArray()) {
            sortByEmail((ArrayNode) java.path("data").path("data"));
            sortByEmail((ArrayNode) nestjs.path("data").path("data"));
        }
        assertThat(java).isEqualTo(nestjs);
    }

    static void sortByEmail(ArrayNode users) {
        List<JsonNode> sorted = new ArrayList<>();
        users.forEach(sorted::add);
        sorted.sort(Comparator.comparing(user -> user.path("email").toString()));
        users.removeAll();
        sorted.forEach(users::add);
    }

    @Test
    void usersForbiddenForUserRole() {
        List<String> tokens = tokens("john.doe@example.com");
        assertSame(authed("GET", "/api/v1/users", tokens, null, Map.of()));
        assertSame(authed("DELETE", "/api/v1/users/abc", tokens, null, Map.of()));
    }

    static Stream<Object> adminCreateErrors() {
        String email = "admin.made." + RUN + "@example.com";
        return Stream.of(
                map(),
                map("email", "john.doe@example.com", "password", PASSWORD),
                map("email", email, "role", map("id", 9)),
                map("email", email, "status", map("id", 7)),
                map("email", email, "photo", map("id", "3f0c8a52-7d0e-4b59-9f8e-2a1d6c4b7e10")));
    }

    @ParameterizedTest
    @MethodSource("adminCreateErrors")
    void adminCreateErrors(Object body) {
        assertSame(authed("POST", "/api/v1/users", tokens("admin@example.com"), body, Map.of()));
    }

    @Test
    void adminCreateUpdateDelete() {
        List<String> tokens = tokens("admin@example.com");
        String email = "lifecycle." + RUN + "@example.com";
        try {
            Parity.Pair created = authed(
                    "POST",
                    "/api/v1/users",
                    tokens,
                    map("email", email, "password", PASSWORD, "firstName", "Life", "role", map("id", 2), "status",
                            map("id", 1), "birthday", "1990-05-06", "gender", 2),
                    Map.of());
            assertSame(created);
            String javaPath = "/api/v1/users/" + created.java().json().path("data").path("id").asInt();
            String nestjsPath = "/api/v1/users/" + created.nestjs().json().path("data").path("id").asInt();
            Map<String, Object> update = map("nickname", "nick", "phone", "139" + RUN.substring(RUN.length() - 8),
                    "firstName", null);
            assertSame(authedEach("PUT", javaPath, nestjsPath, tokens, update, Map.of()));
            assertSame(authedEach("PUT", javaPath, nestjsPath, tokens, map("email", "admin@example.com"), Map.of()));
            assertSame(authedEach("DELETE", javaPath, nestjsPath, tokens, null, Map.of()));
            assertSame(authedEach("GET", javaPath, nestjsPath, tokens, null, Map.of()));
            assertSame(authedEach("PUT", javaPath, nestjsPath, tokens, map("nickname", "after"), Map.of()));
        } finally {
            ParityDb.dropUser(email);
        }
    }
}
