package tech.xingyunqi.template.modules.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.common.redis.MemoryCache;
import tech.xingyunqi.template.integrations.sms.AliyunSmsProvider;
import tech.xingyunqi.template.integrations.sms.SmsProperties;
import tech.xingyunqi.template.integrations.wechat.WechatClient;
import tech.xingyunqi.template.integrations.wechat.WechatProperties;
import tech.xingyunqi.template.modules.notifications.domain.WechatSubscribe;
import tech.xingyunqi.template.support.StubServer;
import tools.jackson.databind.json.JsonMapper;

class ClientNotificationSenderTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SEND_PATH = "/cgi-bin/message/subscribe/send";
    static final WechatSubscribe.Message MESSAGE =
            new WechatSubscribe.Message("TPL", "pages/order", Map.of("character_string1", "N1"));

    final StubServer stub = new StubServer();
    final Cache cache = new MemoryCache(JSON, Clock.systemUTC());

    @AfterEach
    void close() {
        stub.close();
    }

    static SmsProperties sms(String id, String secret, String sign, String template, String endpoint, String mock) {
        return new SmsProperties(id, secret, sign, template, 300, 6, endpoint, mock, 5, 60);
    }

    static WechatProperties wechat(String miniAppId, String base, String templateId, String fields) {
        return new WechatProperties(null, null, miniAppId, "mini-secret", base, null, templateId, fields);
    }

    ClientNotificationSender sender(SmsProperties smsProperties, WechatProperties wechatProperties) {
        return new ClientNotificationSender(
                new AliyunSmsProvider(smsProperties, JSON, Clock.systemUTC()),
                new WechatClient(wechatProperties, cache, JSON),
                wechatProperties);
    }

    ClientNotificationSender wechatSender() {
        stub.on("/cgi-bin/stable_token", StubServer.Reply.json("{\"access_token\":\"T\",\"expires_in\":7200}"));
        return sender(sms(null, null, null, null, null, "true"), wechat("mini", stub.baseUrl(), null, null));
    }

    @Test
    @DisplayName("短信 mock 模式发送成功")
    void smsMockMode() {
        ClientNotificationSender sender =
                sender(sms(null, null, null, null, null, "true"), wechat(null, null, null, null));

        assertThat(sender.sendSms("13800138000", "hi"))
                .isEqualTo(new NotificationSender.SmsResult(true, "Mock SMS sent successfully"));
    }

    @Test
    @DisplayName("短信客户端未初始化或签名 / 模板未设置时返回失败")
    void smsFailures() {
        assertThat(sender(sms(null, null, "S", "T", null, "false"), wechat(null, null, null, null))
                        .sendSms("13800138000", "hi"))
                .isEqualTo(new NotificationSender.SmsResult(false, "SMS client not initialized"));
        assertThat(sender(sms("id", "secret", "", "T", null, "false"), wechat(null, null, null, null))
                        .sendSms("13800138000", "hi"))
                .isEqualTo(new NotificationSender.SmsResult(false, "SMS configuration incomplete"));
    }

    @Test
    @DisplayName("短信以 {content} 为模板参数调用阿里云 SendSms")
    void smsSendsContent() {
        stub.on("/", StubServer.Reply.json("{\"Code\":\"OK\",\"Message\":\"OK\",\"RequestId\":\"r\",\"BizId\":\"b\"}"));
        ClientNotificationSender sender =
                sender(sms("id", "secret", "S", "T", stub.baseUrl(), "false"), wechat(null, null, null, null));

        assertThat(sender.sendSms("13800138000", "hi")).isEqualTo(new NotificationSender.SmsResult(true, "OK"));
        assertThat(stub.requests("/").getFirst().query()).contains("TemplateParam=%7B%22content%22%3A%22hi%22%7D");
    }

    @Test
    @DisplayName("订阅消息模板取自 wechat.subscribeTemplates[scene]，未知场景为 null")
    void subscribeTemplate() {
        ClientNotificationSender sender = sender(
                sms(null, null, null, null, null, "true"),
                wechat(null, null, "TPL", "orderNo:character_string1,amount:amount2"));

        assertThat(sender.subscribeTemplate(WechatSubscribe.ORDER_PAID))
                .isEqualTo(new WechatSubscribe.Template(
                        "TPL", Map.of("orderNo", "character_string1", "amount", "amount2")));
        assertThat(sender.subscribeTemplate("unknown")).isNull();
        assertThat(sender(sms(null, null, null, null, null, "true"), wechat(null, null, null, null))
                        .subscribeTemplate(WechatSubscribe.ORDER_PAID))
                .isEqualTo(new WechatSubscribe.Template("", Map.of()));
    }

    @Test
    @DisplayName("订阅消息发送成功")
    void subscribeSends() {
        stub.on(SEND_PATH, StubServer.Reply.json("{\"errcode\":0,\"errmsg\":\"ok\"}"));

        wechatSender().sendSubscribeMessage("OPEN", MESSAGE);

        assertThat(stub.requests(SEND_PATH).getFirst().body())
                .isEqualTo("{\"touser\":\"OPEN\",\"template_id\":\"TPL\",\"page\":\"pages/order\","
                        + "\"data\":{\"character_string1\":{\"value\":\"N1\"}},"
                        + "\"miniprogram_state\":\"formal\",\"lang\":\"zh_CN\"}");
    }

    @Test
    @DisplayName("不可重试的 errcode 为 unrecoverable 的 SubscribeFailure，其它 errcode 可重试")
    void subscribeErrcode() {
        ClientNotificationSender sender = wechatSender();
        stub.on(SEND_PATH, StubServer.Reply.json("{\"errcode\":43101,\"errmsg\":\"user refuse\"}"));
        assertThatThrownBy(() -> sender.sendSubscribeMessage("OPEN", MESSAGE))
                .isInstanceOfSatisfying(NotificationSender.SubscribeFailure.class, e -> {
                    assertThat(e.unrecoverable()).isTrue();
                    assertThat(e.getMessage()).isEqualTo("WeChat API error 43101: user refuse");
                });

        stub.on(SEND_PATH, StubServer.Reply.json("{\"errcode\":45009,\"errmsg\":\"reach max api daily quota\"}"));
        assertThatThrownBy(() -> sender.sendSubscribeMessage("OPEN", MESSAGE))
                .isInstanceOfSatisfying(
                        NotificationSender.SubscribeFailure.class,
                        e -> assertThat(e.unrecoverable()).isFalse());
    }

    @Test
    @DisplayName("小程序凭证未配置为 unrecoverable，微信 HTTP 错误可重试，消息均为 Unprocessable Entity Exception")
    void subscribeHttpStatusErrors() {
        assertThatThrownBy(
                        () -> sender(sms(null, null, null, null, null, "true"), wechat("", stub.baseUrl(), null, null))
                                .sendSubscribeMessage("OPEN", MESSAGE))
                .isInstanceOfSatisfying(NotificationSender.SubscribeFailure.class, e -> {
                    assertThat(e.unrecoverable()).isTrue();
                    assertThat(e.getMessage()).isEqualTo("Unprocessable Entity Exception");
                    assertThat(e.getCause()).isInstanceOf(HttpStatusException.class);
                });

        ClientNotificationSender sender = wechatSender();
        stub.on(SEND_PATH, new StubServer.Reply(502, "{}"));
        assertThatThrownBy(() -> sender.sendSubscribeMessage("OPEN", MESSAGE))
                .isInstanceOfSatisfying(NotificationSender.SubscribeFailure.class, e -> {
                    assertThat(e.unrecoverable()).isFalse();
                    assertThat(e.getMessage()).isEqualTo("Unprocessable Entity Exception");
                });
    }

    @Test
    @DisplayName("连接失败原样抛出")
    void subscribeTransportError() {
        StubServer closed = new StubServer();
        String base = closed.baseUrl();
        closed.close();
        ClientNotificationSender sender =
                sender(sms(null, null, null, null, null, "true"), wechat("mini", base, null, null));

        assertThatThrownBy(() -> sender.sendSubscribeMessage("OPEN", MESSAGE))
                .isInstanceOf(WechatClient.RequestException.class);
    }

    @Test
    @DisplayName("HTTP 异常消息取响应体的字符串 message，否则为状态描述加 Exception")
    void nestMessage() {
        assertThat(ClientNotificationSender.nestMessage(new HttpStatusException(400, "plain")))
                .isEqualTo("plain");
        assertThat(ClientNotificationSender.nestMessage(new HttpStatusException(400, Map.of("message", "m"))))
                .isEqualTo("m");
        assertThat(ClientNotificationSender.nestMessage(new HttpStatusException(400, Map.of("errors", Map.of()))))
                .isEqualTo("Bad Request Exception");
    }
}
