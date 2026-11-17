package tech.xingyunqi.template.integrations.alipay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.support.StubServer;
import tools.jackson.databind.json.JsonMapper;

class AlipayClientTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T16:30:00Z"), ZoneOffset.UTC);
    static final KeyPair KEYS = keys();

    final StubServer stub = new StubServer();

    static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String privateKey() {
        return Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded());
    }

    @AfterEach
    void close() {
        stub.close();
    }

    AlipayClient client(String signType) {
        return new AlipayClient(
                new AlipayProperties("app1", privateKey(), stub.baseUrl() + "/gateway.do", signType, "", null),
                JSON,
                CLOCK);
    }

    static Map<String, String> query(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    static HttpStatusException thrown(Runnable call) {
        try {
            call.run();
        } catch (HttpStatusException e) {
            return e;
        }
        throw new AssertionError("expected HttpStatusException");
    }

    @Test
    @DisplayName("换取 open_id：公共参数、北京时间时间戳与 RSA2 签名")
    void exchangesAuthCode() throws Exception {
        stub.on(
                "/gateway.do",
                StubServer.Reply.json(
                        "{\"alipay_system_oauth_token_response\":{\"open_id\":\"open-1\",\"access_token\":\"t\"}}"));

        assertThat(client("RSA2").exchangeAuthCode("auth-1")).isEqualTo("open-1");

        StubServer.Request request = stub.requests().getFirst();
        assertThat(request.method()).isEqualTo("GET");
        Map<String, String> params = query(request.query());
        assertThat(params)
                .containsEntry("app_id", "app1")
                .containsEntry("method", "alipay.system.oauth.token")
                .containsEntry("charset", "utf-8")
                .containsEntry("sign_type", "RSA2")
                .containsEntry("timestamp", "2026-01-03 00:30:00")
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "auth-1");
        String sign = params.remove("sign");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KEYS.getPublic());
        verifier.update(AlipayClient.signString(params).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(sign))).isTrue();
    }

    @Test
    @DisplayName("RSA 签名类型使用 SHA1withRSA，PEM 头尾可省略")
    void rsaSignature() throws Exception {
        Map<String, String> params = Map.of("b", "2", "a", "1", "empty", "");
        String pem = "-----BEGIN PRIVATE KEY-----\n" + privateKey() + "\n-----END PRIVATE KEY-----";
        String sign = AlipayClient.sign(params, pem, "RSA");
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(KEYS.getPublic());
        verifier.update("a=1&b=2".getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(sign))).isTrue();
        assertThatThrownBy(() -> AlipayClient.sign(params, "not-a-key", "RSA2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("未配置 appId 或私钥时为 alipay: notConfigured")
    void notConfigured() {
        AlipayClient client = new AlipayClient(new AlipayProperties("app", "", null, null, null, null), JSON, CLOCK);
        HttpStatusException error = thrown(() -> client.exchangeAuthCode("x"));
        assertThat(error.status()).isEqualTo(422);
        assertThat(error.response().toString()).isEqualTo("{status=422, errors={alipay=notConfigured}}");
    }

    @Test
    @DisplayName("isv.code-* 为 invalidAlipayAuthCode，其它业务错误、缺 open_id、HTTP 错误、非 JSON 与空响应体为 requestFailed")
    void errors() {
        stub.on(
                "/gateway.do",
                StubServer.Reply.json("{\"error_response\":{\"code\":\"40002\",\"sub_code\":\"isv.code-invalid\"}}"));
        assertThat(thrown(() -> client("RSA2").exchangeAuthCode("x")).response().toString())
                .isEqualTo("{status=422, error=invalidAlipayAuthCode, errors={authCode=invalidCode}}");

        String requestFailed = "{status=422, errors={alipay=requestFailed}}";
        stub.on("/gateway.do", StubServer.Reply.json("{\"error_response\":{\"code\":\"40004\"}}"));
        assertThat(thrown(() -> client("RSA2").exchangeAuthCode("x")).response().toString())
                .isEqualTo(requestFailed);

        stub.on(
                "/gateway.do",
                StubServer.Reply.json("{\"alipay_system_oauth_token_response\":{\"user_id\":\"2088\"}}"));
        assertThat(thrown(() -> client("RSA2").exchangeAuthCode("x")).response().toString())
                .isEqualTo(requestFailed);

        stub.on("/gateway.do", new StubServer.Reply(503, ""));
        assertThat(thrown(() -> client("RSA2").exchangeAuthCode("x")).response().toString())
                .isEqualTo(requestFailed);

        stub.on("/gateway.do", new StubServer.Reply(200, "<html>"));
        assertThat(thrown(() -> client("RSA2").exchangeAuthCode("x")).response().toString())
                .isEqualTo(requestFailed);

        stub.on("/gateway.do", new StubServer.Reply(200, ""));
        assertThat(thrown(() -> client("RSA2").exchangeAuthCode("x")).response().toString())
                .isEqualTo(requestFailed);
    }

    @Test
    @DisplayName("网关按 ALIPAY_GATEWAY、ALIPAY_SANDBOX 取值，签名类型只接受 RSA2 / RSA")
    void properties() {
        assertThat(new AlipayProperties(null, null, "", null, null, "true").gatewayOrDefault())
                .isEqualTo(AlipayProperties.SANDBOX_GATEWAY);
        assertThat(new AlipayProperties(null, null, null, null, null, "false").gatewayOrDefault())
                .isEqualTo(AlipayProperties.GATEWAY);
        assertThat(new AlipayProperties(null, null, "http://g", null, null, null).gatewayOrDefault())
                .isEqualTo("http://g");
        assertThat(new AlipayProperties(null, null, null, "", "", null).signTypeOrDefault())
                .isEqualTo("RSA2");
        assertThat(new AlipayProperties(null, null, null, null, "gbk", null).charsetOrDefault())
                .isEqualTo("gbk");
        assertThat(new AlipayProperties("a", null, null, null, null, null).configured())
                .isTrue();
        assertThatThrownBy(() -> new AlipayProperties(null, null, null, "MD5", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
