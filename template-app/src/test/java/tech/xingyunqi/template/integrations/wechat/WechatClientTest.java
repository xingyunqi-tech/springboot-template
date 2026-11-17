package tech.xingyunqi.template.integrations.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.common.redis.MemoryCache;
import tech.xingyunqi.template.support.StubServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WechatClientTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    final StubServer stub = new StubServer();
    final Cache cache = new MemoryCache(JSON, Clock.systemUTC());

    @AfterEach
    void close() {
        stub.close();
    }

    WechatClient client(String base) {
        return new WechatClient(
                new WechatProperties("app", "app-secret", "mini", "mini-secret", base, null, null, null), cache, JSON);
    }

    WechatClient client() {
        return client(stub.baseUrl());
    }

    static Map<String, Object> errors(HttpStatusException e) {
        assertThat(e.status()).isEqualTo(422);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) e.response();
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) body.get("errors");
        return errors;
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
    @DisplayName("小程序 code2session 成功返回 openId / unionId / sessionKey")
    void miniAppLogin() {
        stub.on(
                "/sns/jscode2session",
                StubServer.Reply.json("{\"openid\":\"o1\",\"unionid\":\"u1\",\"session_key\":\"k\"}"));

        WechatClient.UserInfo info = client().miniAppLogin("c&1");

        assertThat(info).isEqualTo(new WechatClient.UserInfo("o1", "u1", "k", null, null, null));
        assertThat(stub.requests().getFirst().query())
                .isEqualTo("appid=mini&secret=mini-secret&js_code=c%261&grant_type=authorization_code");
    }

    @Test
    @DisplayName("小程序登录：未配置、errcode、HTTP 错误与连接失败分别映射为 422")
    void miniAppLoginErrors() throws Exception {
        WechatClient unconfigured = new WechatClient(
                new WechatProperties(null, null, "", "", stub.baseUrl(), null, null, null), cache, JSON);
        assertThat(errors(thrown(() -> unconfigured.miniAppLogin("c")))).isEqualTo(Map.of("wechat", "notConfigured"));

        stub.on("/sns/jscode2session", StubServer.Reply.json("{\"errcode\":40029,\"errmsg\":\"invalid code\"}"));
        Map<String, Object> invalid = errors(thrown(() -> client().miniAppLogin("c")));
        assertThat(invalid).containsEntry("code", "invalidCode");
        assertThat(invalid.get("wechatError").toString()).isEqualTo("\"invalid code\"");

        stub.on("/sns/jscode2session", new StubServer.Reply(500, "{}"));
        assertThat(errors(thrown(() -> client().miniAppLogin("c")))).isEqualTo(Map.of("wechat", "requestFailed"));

        stub.on("/sns/jscode2session", new StubServer.Reply(200, "oops"));
        assertThat(errors(thrown(() -> client().miniAppLogin("c")))).isEqualTo(Map.of("wechat", "requestFailed"));

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        WechatClient closed = client("http://127.0.0.1:" + port);
        assertThat(errors(thrown(() -> closed.miniAppLogin("c")))).isEqualTo(Map.of("wechat", "requestFailed"));
    }

    @Test
    @DisplayName("Jackson 解析空串或纯空白得到 MissingNode 而不抛异常")
    void jacksonEmptyInput() {
        assertThat(JSON.readTree("").isMissingNode()).isTrue();
        assertThat(JSON.readTree(" \n\t").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("响应体为空或纯空白时为 wechat: requestFailed")
    void emptyBodyIsRequestFailure() {
        for (String body : new String[] {"", "  \n"}) {
            stub.on("/sns/jscode2session", new StubServer.Reply(200, body));
            assertThat(errors(thrown(() -> client().miniAppLogin("c")))).isEqualTo(Map.of("wechat", "requestFailed"));

            stub.on("/sns/oauth2/access_token", new StubServer.Reply(200, body));
            assertThat(errors(thrown(() -> client().appLogin("c")))).isEqualTo(Map.of("wechat", "requestFailed"));

            stub.on("/cgi-bin/stable_token", StubServer.Reply.json("{\"access_token\":\"T\",\"expires_in\":7200}"));
            stub.on("/wxa/business/getuserphonenumber", new StubServer.Reply(200, body));
            assertThat(errors(thrown(() -> client().getPhoneNumber("p")))).isEqualTo(Map.of("wechat", "requestFailed"));
            assertThatThrownBy(() -> client().callMiniAppApi("/wxa/business/getuserphonenumber", Map.of(), "Call"))
                    .isInstanceOf(WechatClient.RequestException.class);
        }
    }

    @Test
    @DisplayName("APP 登录先换 access_token 再取用户信息，unionId 优先取用户信息中的值")
    void appLogin() {
        stub.on(
                "/sns/oauth2/access_token",
                StubServer.Reply.json("{\"access_token\":\"at\",\"openid\":\"o2\",\"unionid\":\"u-token\"}"));
        stub.on(
                "/sns/userinfo",
                StubServer.Reply.json("{\"openid\":\"o2\",\"nickname\":\"N\",\"sex\":1,\"headimgurl\":\"h\"}"));

        WechatClient.UserInfo info = client().appLogin("c");

        assertThat(info).isEqualTo(new WechatClient.UserInfo("o2", "u-token", null, "N", "h", 1));
        assertThat(stub.requests("/sns/userinfo").getFirst().query()).isEqualTo("access_token=at&openid=o2");
    }

    @Test
    @DisplayName("APP 登录：未配置、换 token 失败与取用户信息失败")
    void appLoginErrors() {
        WechatClient unconfigured = new WechatClient(
                new WechatProperties("", "", "mini", "s", stub.baseUrl(), null, null, null), cache, JSON);
        assertThat(errors(thrown(() -> unconfigured.appLogin("c")))).isEqualTo(Map.of("wechat", "notConfigured"));

        stub.on("/sns/oauth2/access_token", StubServer.Reply.json("{\"errcode\":40163,\"errmsg\":\"used\"}"));
        assertThat(errors(thrown(() -> client().appLogin("c")))).containsEntry("code", "invalidCode");

        stub.on("/sns/oauth2/access_token", StubServer.Reply.json("{\"access_token\":\"at\",\"openid\":\"o\"}"));
        stub.on("/sns/userinfo", StubServer.Reply.json("{\"errcode\":40003}"));
        assertThat(errors(thrown(() -> client().appLogin("c")))).isEqualTo(Map.of("wechat", "userInfoFailed"));

        stub.on("/sns/userinfo", new StubServer.Reply(502, ""));
        assertThat(errors(thrown(() -> client().appLogin("c")))).isEqualTo(Map.of("wechat", "requestFailed"));
    }

    @Test
    @DisplayName("手机号：stable_token 缓存复用，返回纯手机号与区号")
    void phoneNumber() {
        AtomicInteger tokens = new AtomicInteger();
        stub.on("/cgi-bin/stable_token", request -> {
            tokens.incrementAndGet();
            return StubServer.Reply.json("{\"access_token\":\"T1\",\"expires_in\":7200}");
        });
        stub.on(
                "/wxa/business/getuserphonenumber",
                StubServer.Reply.json("{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800000000\","
                        + "\"countryCode\":\"86\"}}"));

        assertThat(client().getPhoneNumber("p")).isEqualTo(new WechatClient.PhoneInfo("13800000000", "86"));
        assertThat(client().getPhoneNumber("p")).isEqualTo(new WechatClient.PhoneInfo("13800000000", "86"));

        assertThat(tokens).hasValue(1);
        StubServer.Request call =
                stub.requests("/wxa/business/getuserphonenumber").getFirst();
        assertThat(call.query()).isEqualTo("access_token=T1");
        assertThat(call.body()).isEqualTo("{\"code\":\"p\"}");
        assertThat(stub.requests("/cgi-bin/stable_token").getFirst().body())
                .isEqualTo("{\"grant_type\":\"client_credential\",\"appid\":\"mini\",\"secret\":\"mini-secret\","
                        + "\"force_refresh\":false}");
    }

    @Test
    @DisplayName("access_token 失效时刷新一次并重试；普通模式仍返回旧 token 时强制刷新")
    void refreshesInvalidToken() {
        AtomicInteger calls = new AtomicInteger();
        stub.on(
                "/cgi-bin/stable_token",
                request -> StubServer.Reply.json(
                        request.body().contains("\"force_refresh\":true")
                                ? "{\"access_token\":\"T2\",\"expires_in\":600}"
                                : "{\"access_token\":\"T1\",\"expires_in\":7200}"));
        stub.on(
                "/wxa/business/getuserphonenumber",
                request -> StubServer.Reply.json(
                        calls.incrementAndGet() == 1
                                ? "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}"
                                : "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"1\",\"countryCode\":\"1\"}}"));

        assertThat(client().getPhoneNumber("p")).isEqualTo(new WechatClient.PhoneInfo("1", "1"));
        assertThat(stub.requests("/wxa/business/getuserphonenumber").get(1).query())
                .isEqualTo("access_token=T2");
        assertThat(client().getMiniAppAccessToken()).isEqualTo("T2");
    }

    @Test
    @DisplayName("手机号 code 无效为 invalidPhoneCode，其它失败为 requestFailed")
    void phoneNumberErrors() {
        stub.on("/cgi-bin/stable_token", StubServer.Reply.json("{\"access_token\":\"T\",\"expires_in\":7200}"));
        stub.on("/wxa/business/getuserphonenumber", StubServer.Reply.json("{\"errcode\":40029,\"errmsg\":\"bad\"}"));
        HttpStatusException invalid = thrown(() -> client().getPhoneNumber("p"));
        assertThat(errors(invalid)).isEqualTo(Map.of("phoneCode", "invalidCode"));
        assertThat(((Map<?, ?>) invalid.response()).get("error")).isEqualTo("invalidPhoneCode");

        stub.on("/wxa/business/getuserphonenumber", StubServer.Reply.json("{\"errcode\":0}"));
        assertThat(errors(thrown(() -> client().getPhoneNumber("p")))).isEqualTo(Map.of("wechat", "requestFailed"));

        stub.on("/wxa/business/getuserphonenumber", new StubServer.Reply(200, "nope"));
        assertThat(errors(thrown(() -> client().getPhoneNumber("p")))).isEqualTo(Map.of("wechat", "requestFailed"));

        cache.delete(WechatClient.MINI_APP_ACCESS_TOKEN_CACHE_KEY);
        stub.on("/cgi-bin/stable_token", StubServer.Reply.json("{\"errcode\":40013,\"errmsg\":\"invalid appid\"}"));
        assertThat(errors(thrown(() -> client().getPhoneNumber("p")))).isEqualTo(Map.of("wechat", "requestFailed"));

        WechatClient unconfigured =
                new WechatClient(new WechatProperties("a", "b", null, null, null, null, null, null), cache, JSON);
        assertThat(errors(thrown(() -> unconfigured.getPhoneNumber("p")))).isEqualTo(Map.of("wechat", "notConfigured"));
    }

    @Test
    @DisplayName("callMiniAppApi 对非 0 errcode 抛 ApiException")
    void callMiniAppApiErrors() {
        stub.on("/cgi-bin/stable_token", StubServer.Reply.json("{\"access_token\":\"T\",\"expires_in\":100}"));
        stub.on("/cgi-bin/message/subscribe/send", StubServer.Reply.json("{\"errcode\":43101,\"errmsg\":\"refuse\"}"));

        assertThatThrownBy(() -> client().callMiniAppApi("/cgi-bin/message/subscribe/send", Map.of("a", 1), "Send"))
                .isInstanceOfSatisfying(WechatClient.ApiException.class, e -> {
                    assertThat(e.errcode()).isEqualTo(43101);
                    assertThat(e.errmsg()).isEqualTo("refuse");
                });
        JsonNode ok = JSON.readTree("{\"errcode\":0}");
        stub.on("/cgi-bin/message/subscribe/send", StubServer.Reply.json(ok.toString()));
        assertThat(client().callMiniAppApi("/cgi-bin/message/subscribe/send", Map.of(), "Send"))
                .isEqualTo(ok);
    }

    @Test
    @DisplayName("默认接口根地址为 api.weixin.qq.com")
    void defaultApiBase() {
        assertThat(new WechatProperties(null, null, null, null, null, null, null, null).apiBaseOrDefault())
                .isEqualTo("https://api.weixin.qq.com");
        assertThat(new WechatProperties("a", "", null, null, null, null, null, null).configured())
                .isFalse();
    }
}
