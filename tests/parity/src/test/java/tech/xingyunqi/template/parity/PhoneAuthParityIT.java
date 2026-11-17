package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.xingyunqi.template.parity.AuthParityIT.PASSWORD;
import static tech.xingyunqi.template.parity.AuthParityIT.assertSame;
import static tech.xingyunqi.template.parity.AuthParityIT.json;
import static tech.xingyunqi.template.parity.AuthParityIT.map;
import static tech.xingyunqi.template.parity.AuthParityIT.md5;
import static tech.xingyunqi.template.parity.AuthParityIT.normalize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 与 fastapi-temp {@code tests/parity/test_phone_auth_parity.py} 一一对应；验证码经 {@code redis-cli} 从两侧 Redis 读取。 */
class PhoneAuthParityIT {

    static final String JAVA_REDIS =
            System.getProperty("PARITY_JAVA_REDIS_CONTAINER", "springboot-template-infra-redis-1");
    static final String NESTJS_REDIS =
            System.getProperty("PARITY_NESTJS_REDIS_CONTAINER", "springboot-template-nestjs-redis-1");
    static final String SEND = "/api/v1/auth/phone/send-code";
    private static final AtomicLong PHONES = new AtomicLong(System.currentTimeMillis() % 100_000_000L);

    private final Parity parity = Parity.fromSystemProperties();

    static String newPhone() {
        return String.format(Locale.ROOT, "139%08d", PHONES.incrementAndGet() % 100_000_000L);
    }

    static String redisGet(String container, String key) {
        try {
            Process process = new ProcessBuilder("docker", "exec", container, "redis-cli", "--raw", "GET", key)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("redis-cli failed: " + output);
            }
            return output.strip();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 两侧各自存储的验证码：(java, nestjs)。 */
    static List<String> codes(String phone, String type) {
        String key = "cache::sms:code:" + type + ":" + phone;
        List<String> codes = new ArrayList<>();
        for (String container : List.of(JAVA_REDIS, NESTJS_REDIS)) {
            String raw = redisGet(container, key);
            assertThat(raw).as("%s has no %s", container, key).isNotEmpty();
            codes.add(Parity.JSON.readTree(raw).path("value").path("code").asString());
        }
        return codes;
    }

    Parity.Pair post(String path, Object body, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("Content-Type", "application/json");
        return parity.request("POST", path, all, json(body));
    }

    Parity.Pair post(String path, Object body) {
        return post(path, body, Map.of());
    }

    /** 两侧分别发送各自的请求体：(java, nestjs)。 */
    Parity.Pair perSide(String path, Map<String, Object> javaBody, Map<String, Object> nestjsBody) {
        return parity.requestEachBody(
                "POST", path, Map.of("Content-Type", "application/json"), json(javaBody), json(nestjsBody));
    }

    Parity.Pair withCodes(String path, Map<String, Object> body, List<String> codes) {
        Map<String, Object> javaBody = new LinkedHashMap<>(body);
        javaBody.put("code", codes.get(0));
        Map<String, Object> nestjsBody = new LinkedHashMap<>(body);
        nestjsBody.put("code", codes.get(1));
        return perSide(path, javaBody, nestjsBody);
    }

    void send(String phone, String type) {
        JsonNode body = assertSame(post(SEND, map("phone", phone, "type", type)));
        assertThat(body.toString()).isEqualTo("{\"code\":200,\"message\":\"success\"}");
    }

    Parity.Pair register(String phone, Map<String, Object> extra) {
        send(phone, "register");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("password", PASSWORD);
        body.putAll(extra);
        return withCodes("/api/v1/auth/phone/register", body, codes(phone, "register"));
    }

    static void assertSameLogin(Parity.Pair pair) {
        String detail = new String(pair.java().body(), StandardCharsets.UTF_8) + "\n"
                + new String(pair.nestjs().body(), StandardCharsets.UTF_8);
        assertThat(pair.java().status()).as(detail).isEqualTo(pair.nestjs().status());
        Parity.assertSameJson(normalize(pair.java().json(), null), normalize(pair.nestjs().json(), null), detail);
    }

    static List<String> tokens(Parity.Pair pair) {
        return List.of(
                pair.java().json().path("data").path("token").asString(),
                pair.nestjs().json().path("data").path("token").asString());
    }

    Parity.Pair authed(String method, String path, List<String> tokens, Object body) {
        Map<String, String> javaHeaders = new LinkedHashMap<>();
        Map<String, String> nestjsHeaders = new LinkedHashMap<>();
        javaHeaders.put("Authorization", "Bearer " + tokens.get(0));
        nestjsHeaders.put("Authorization", "Bearer " + tokens.get(1));
        byte[] payload = new byte[0];
        if (body != null) {
            payload = json(body);
            javaHeaders.put("Content-Type", "application/json");
            nestjsHeaders.put("Content-Type", "application/json");
        }
        return parity.requestEach(method, path, javaHeaders, path, nestjsHeaders, payload);
    }

    static Stream<Arguments> errorCases() {
        List<List<Object>> cases = List.of(
                List.of(SEND, map()),
                List.of(SEND, map("phone", "123", "type", "nope")),
                List.of(SEND, map("phone", "13800000000", "type", "change_phone")),
                List.of(SEND, map("phone", "13000000001")),
                List.of(SEND, map("phone", "13000000001", "type", "reset_password")),
                List.of(SEND, map("phone", "13000000001", "type", "verify_identity")),
                List.of("/api/v1/auth/phone/login", map()),
                List.of("/api/v1/auth/phone/login", map("phone", "13000000001", "password", PASSWORD)),
                List.of("/api/v1/auth/phone/login", map("phone", "13000000001", "password", "short")),
                List.of("/api/v1/auth/phone/sms-login", map()),
                List.of("/api/v1/auth/phone/sms-login", map("phone", "13000000001", "code", "123456")),
                List.of("/api/v1/auth/phone/sms-login", map("phone", "13000000001", "code", "12")),
                List.of("/api/v1/auth/phone/register", map()),
                List.of(
                        "/api/v1/auth/phone/register",
                        map("phone", "13000000001", "code", "123456", "password", PASSWORD, "nickname", 1)),
                List.of(
                        "/api/v1/auth/phone/register",
                        map("phone", "13000000001", "code", "123456", "password", PASSWORD)),
                List.of("/api/v1/auth/phone/reset-password", map()),
                List.of(
                        "/api/v1/auth/phone/reset-password",
                        map("phone", "13000000001", "code", "12ab", "password", PASSWORD)),
                List.of(
                        "/api/v1/auth/phone/reset-password",
                        map("phone", "13000000001", "code", "123456", "password", PASSWORD)),
                List.of("/api/v1/auth/wechat/login", map()),
                List.of("/api/v1/auth/wechat/login", map("code", "c", "type", "web")),
                List.of("/api/v1/auth/wechat/login", map("code", "c", "type", "mini_app")),
                List.of("/api/v1/auth/wechat/login", map("code", "c", "type", "app", "nickname", "n")),
                List.of("/api/v1/auth/wechat/phone-login", map()),
                List.of("/api/v1/auth/wechat/phone-login", map("loginCode", "a", "phoneCode", "b")),
                List.of("/api/v1/auth/alipay/login", map()),
                List.of("/api/v1/auth/alipay/login", map("authCode", "x")));
        List<Arguments> arguments = new ArrayList<>();
        for (String lang : List.of("en", "zh-CN")) {
            for (List<Object> c : cases) {
                arguments.add(Arguments.of(c.get(0), c.get(1), lang));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("errorCases")
    void errors(String url, Object body, String lang) {
        assertSame(post(url, body, Map.of("Accept-Language", lang)));
    }

    @Test
    void registerAndLoginFlows() {
        String phone = newPhone();
        Parity.Pair registered = register(phone, map("nickname", "  Phone User "));
        assertSameLogin(registered);
        assertThat(registered.java().json().path("data").path("user").path("phone").asString())
                .isEqualTo(phone);

        send(phone, "login");
        Parity.Pair again = post(SEND, map("phone", phone));
        assertThat(again.java().status()).isEqualTo(422);
        assertThat(again.nestjs().status()).isEqualTo(422);
        ObjectNode javaData = (ObjectNode) again.java().json().path("data").deepCopy();
        ObjectNode nestjsData = (ObjectNode) again.nestjs().json().path("data").deepCopy();
        assertThat(javaData.path("retryAfter").isIntegralNumber()).isTrue();
        assertThat(nestjsData.path("retryAfter").isIntegralNumber()).isTrue();
        javaData.remove("retryAfter");
        nestjsData.remove("retryAfter");
        assertThat(javaData).isEqualTo(nestjsData);
        assertThat(again.java().json().path("message")).isEqualTo(again.nestjs().json().path("message"));

        List<String> codes = codes(phone, "login");
        assertSame(post("/api/v1/auth/phone/sms-login", map("phone", phone, "code", "0000000")));
        assertSameLogin(withCodes("/api/v1/auth/phone/sms-login", map("phone", phone), codes));

        for (Object body : List.of(
                map("phone", phone, "password", md5("wrong")),
                map("phone", phone, "password", PASSWORD.toUpperCase(Locale.ROOT)))) {
            assertSameLogin(post("/api/v1/auth/phone/login", body));
        }
    }

    @Test
    void resetPasswordFlow() {
        String phone = newPhone();
        List<String> tokens = tokens(register(phone, Map.of()));
        send(phone, "reset_password");
        String fresh = md5("NewSecret01");
        Parity.Pair reset = withCodes(
                "/api/v1/auth/phone/reset-password",
                map("phone", phone, "password", fresh),
                codes(phone, "reset_password"));
        assertThat(assertSame(reset).toString()).isEqualTo("{\"code\":200,\"message\":\"success\"}");
        assertSame(authed("GET", "/api/v1/auth/me", tokens, null));
        for (String password : List.of(PASSWORD, fresh)) {
            assertSameLogin(post("/api/v1/auth/phone/login", map("phone", phone, "password", password)));
        }
    }

    @Test
    void registerExistingPhone() {
        String phone = newPhone();
        register(phone, Map.of());
        assertSame(post(SEND, map("phone", phone, "type", "register")));
        for (String lang : List.of("en", "zh")) {
            assertSame(post(
                    "/api/v1/auth/phone/register",
                    map("phone", phone, "code", "123456", "password", PASSWORD),
                    Map.of("Accept-Language", lang)));
        }
    }

    @Test
    void identityCheckForPasswordUser() {
        String phone = newPhone();
        List<String> tokens = tokens(register(phone, Map.of()));
        for (Object body : List.of(map(), map("smsCode", "123456"), map("oldPassword", md5("wrong")))) {
            assertSame(authed("DELETE", "/api/v1/auth/me", tokens, body));
        }
        send(phone, "verify_identity");
        assertSame(post(SEND, map("phone", phone, "type", "verify_identity")));
    }
}
