package tech.xingyunqi.template.integrations.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.support.StubServer;
import tools.jackson.databind.json.JsonMapper;

class AliyunSmsProviderTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

    final StubServer stub = new StubServer();

    @AfterEach
    void close() {
        stub.close();
    }

    static SmsProperties properties(
            String id, String secret, String sign, String template, String endpoint, String mock) {
        return new SmsProperties(id, secret, sign, template, 300, 6, endpoint, mock, 5, 60);
    }

    AliyunSmsProvider provider(String sign, String template) {
        return new AliyunSmsProvider(properties("ak", "sk", sign, template, stub.baseUrl(), "false"), JSON, CLOCK);
    }

    @Test
    @DisplayName("mock 模式只打日志并返回成功")
    void mockMode() {
        AliyunSmsProvider provider = new AliyunSmsProvider(properties("", "", "", "", null, "true"), JSON, CLOCK);
        AliyunSmsProvider.SendSmsResult result = provider.sendSms("13800000000", Map.of("code", "123456"));
        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.requestId()).isEqualTo("mock-request-id");
        assertThat(result.bizId()).isEqualTo("mock-biz-id");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    @DisplayName("未配置凭证时返回 CLIENT_NOT_INITIALIZED")
    void clientNotInitialized() {
        AliyunSmsProvider provider = new AliyunSmsProvider(properties("ak", "", "s", "t", null, null), JSON, CLOCK);
        AliyunSmsProvider.SendSmsResult result = provider.sendSms("13800000000", Map.of("code", "1"));
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CLIENT_NOT_INITIALIZED");
        assertThat(result.message()).isEqualTo("SMS client not initialized");
    }

    @Test
    @DisplayName("签名或模板未配置时返回 CONFIG_ERROR")
    void configError() {
        assertThat(provider("", "T1").sendSms("13800000000", Map.of()).code()).isEqualTo("CONFIG_ERROR");
        assertThat(provider("S", "").sendSms("13800000000", Map.of()).message())
                .isEqualTo("SMS configuration incomplete");
    }

    @Test
    @DisplayName("调用 SendSms：查询参数、ACS3 签名头与成功响应")
    void sendsSignedRequest() {
        stub.on(
                "/",
                StubServer.Reply.json("{\"Code\":\"OK\",\"Message\":\"OK\",\"RequestId\":\"r1\",\"BizId\":\"b1\"}"));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", "654321");
        AliyunSmsProvider.SendSmsResult result = provider("签名", "SMS_1").sendSms("13800000000", params);

        assertThat(result).isEqualTo(new AliyunSmsProvider.SendSmsResult(true, "OK", "OK", "r1", "b1"));
        StubServer.Request request = stub.requests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.query())
                .isEqualTo("PhoneNumbers=13800000000&SignName=%E7%AD%BE%E5%90%8D&TemplateCode=SMS_1"
                        + "&TemplateParam=%7B%22code%22%3A%22654321%22%7D");
        assertThat(request.header("x-acs-action")).isEqualTo("SendSms");
        assertThat(request.header("x-acs-version")).isEqualTo("2017-05-25");
        assertThat(request.header("x-acs-date")).isEqualTo("2026-01-02T03:04:05Z");
        assertThat(request.header("authorization"))
                .startsWith("ACS3-HMAC-SHA256 Credential=ak,SignedHeaders=host;x-acs-action;x-acs-content-sha256;"
                        + "x-acs-date;x-acs-signature-nonce;x-acs-version,Signature=");
    }

    @Test
    @DisplayName("签名与 fastapi-temp sign_request 的计算结果一致")
    void signatureMatchesReference() {
        Map<String, String> headers = AliyunSmsProvider.signedHeaders(
                "dysmsapi.aliyuncs.com", "A=1&B=%20x", "ak", "sk", "2026-01-02T03:04:05Z", "nonce");
        assertThat(headers.get("x-acs-content-sha256"))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(headers.get("authorization"))
                .endsWith(",Signature=f26f4ae3f57f7a85c445ef2a11dde82e28589766f984b5312ef7c653fd005cef");
        assertThat(AliyunSmsProvider.canonicalQuery(Map.of("B", " x", "A", "1")))
                .isEqualTo("A=1&B=%20x");
        assertThat(AliyunSmsProvider.encode("a b*~")).isEqualTo("a%20b%2A~");
    }

    @Test
    @DisplayName("阿里云返回非 OK 时带回错误码与消息")
    void businessFailure() {
        stub.on(
                "/",
                StubServer.Reply.json(
                        "{\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\",\"Message\":\"limit\",\"RequestId\":\"r\"}"));
        AliyunSmsProvider.SendSmsResult result = provider("S", "T").sendSms("13800000000", Map.of("code", "1"));
        assertThat(result)
                .isEqualTo(
                        new AliyunSmsProvider.SendSmsResult(false, "isv.BUSINESS_LIMIT_CONTROL", "limit", "r", null));
    }

    @Test
    @DisplayName("HTTP 错误、非 JSON 与连接失败均为 SEND_ERROR，消息不含 URL query")
    void sendErrors() throws Exception {
        stub.on("/", new StubServer.Reply(400, "{\"Code\":\"InvalidParam\",\"Message\":\"bad\"}"));
        assertThat(provider("S", "T").sendSms("13800000000", Map.of()))
                .extracting(AliyunSmsProvider.SendSmsResult::code, AliyunSmsProvider.SendSmsResult::message)
                .containsExactly("SEND_ERROR", "InvalidParam: bad");

        stub.on("/", new StubServer.Reply(200, "not json"));
        assertThat(provider("S", "T").sendSms("13800000000", Map.of()).code()).isEqualTo("SEND_ERROR");

        stub.on("/", new StubServer.Reply(200, "[1]"));
        assertThat(provider("S", "T").sendSms("13800000000", Map.of()).message())
                .isEqualTo("SMS response body is empty");

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        AliyunSmsProvider closed =
                new AliyunSmsProvider(properties("ak", "sk", "S", "T", "http://127.0.0.1:" + port, null), JSON, CLOCK);
        AliyunSmsProvider.SendSmsResult result = closed.sendSms("13800000000", Map.of("code", "1"));
        assertThat(result.code()).isEqualTo("SEND_ERROR");
        assertThat(result.message()).doesNotContain("13800000000").doesNotContain("?");
    }

    @Test
    @DisplayName("endpoint 不带协议时使用 https")
    void baseUri() {
        assertThat(AliyunSmsProvider.baseUri("dysmsapi.aliyuncs.com").toString())
                .isEqualTo("https://dysmsapi.aliyuncs.com");
        assertThat(AliyunSmsProvider.baseUri("http://127.0.0.1:1/").toString()).isEqualTo("http://127.0.0.1:1");
    }

    @Test
    @DisplayName("手机号脱敏保留前 3 位与后 4 位")
    void maskPhone() {
        assertThat(PhoneMask.mask("13812345678")).isEqualTo("138****5678");
        assertThat(PhoneMask.mask("123")).isEqualTo("***");
        assertThat(PhoneMask.mask("")).isEqualTo("[invalid]");
        assertThat(PhoneMask.mask(null)).isEqualTo("[invalid]");
    }
}
