package tech.xingyunqi.template.integrations.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.common.redis.MemoryCache;
import tech.xingyunqi.template.support.StubServer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

class WechatSubscribeMessageTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PATH = "/cgi-bin/message/subscribe/send";

    final StubServer stub = new StubServer();
    final Cache cache = new MemoryCache(JSON, Clock.systemUTC());

    @AfterEach
    void close() {
        stub.close();
    }

    WechatClient client(String state) {
        cache.set(WechatClient.MINI_APP_ACCESS_TOKEN_CACHE_KEY, StringNode.valueOf("T1"), 60_000);
        return new WechatClient(
                new WechatProperties(null, null, "mini", "mini-secret", stub.baseUrl(), state, null, null),
                cache,
                JSON);
    }

    @Test
    @DisplayName("订阅消息以 access_token POST，data 每项包成 {value}，带小程序版本与 zh_CN")
    void sendsMessage() {
        stub.on(PATH, StubServer.Reply.json("{\"errcode\":0,\"errmsg\":\"ok\"}"));
        Map<String, String> data = new LinkedHashMap<>();
        data.put("character_string1", "ORD1");
        data.put("amount2", "9.90");

        client("trial").sendSubscribeMessage("OPEN1", "TPL1", "pages/index/index", data);

        StubServer.Request request = stub.requests(PATH).getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.query()).isEqualTo("access_token=T1");
        assertThat(request.body())
                .isEqualTo("{\"touser\":\"OPEN1\",\"template_id\":\"TPL1\",\"page\":\"pages/index/index\","
                        + "\"data\":{\"character_string1\":{\"value\":\"ORD1\"},\"amount2\":{\"value\":\"9.90\"}},"
                        + "\"miniprogram_state\":\"trial\",\"lang\":\"zh_CN\"}");
    }

    @Test
    @DisplayName("page 为空时不带 page，小程序版本未配置时为 formal")
    void omitsPage() {
        stub.on(PATH, StubServer.Reply.json("{\"errcode\":0}"));

        client(null).sendSubscribeMessage("OPEN1", "TPL1", null, Map.of());

        assertThat(stub.requests(PATH).getFirst().body())
                .isEqualTo("{\"touser\":\"OPEN1\",\"template_id\":\"TPL1\",\"data\":{},"
                        + "\"miniprogram_state\":\"formal\",\"lang\":\"zh_CN\"}");
    }

    @Test
    @DisplayName("微信返回非 0 errcode 时抛出带 errcode 的 ApiException")
    void apiError() {
        stub.on(PATH, StubServer.Reply.json("{\"errcode\":43101,\"errmsg\":\"user refuse to accept the msg\"}"));

        assertThatThrownBy(() -> client("formal").sendSubscribeMessage("OPEN1", "TPL1", null, Map.of()))
                .isInstanceOfSatisfying(WechatClient.ApiException.class, e -> {
                    assertThat(e.errcode()).isEqualTo(43101);
                    assertThat(e.getMessage()).isEqualTo("WeChat API error 43101: user refuse to accept the msg");
                });
    }
}
