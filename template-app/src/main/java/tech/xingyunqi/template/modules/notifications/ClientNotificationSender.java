package tech.xingyunqi.template.modules.notifications;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.core.exception.HttpStatusNames;
import tech.xingyunqi.template.integrations.sms.AliyunSmsProvider;
import tech.xingyunqi.template.integrations.wechat.WechatClient;
import tech.xingyunqi.template.integrations.wechat.WechatProperties;
import tech.xingyunqi.template.modules.notifications.domain.WechatSubscribe;

/** 以阿里云短信与微信小程序客户端发送通知。 */
@Component
class ClientNotificationSender implements NotificationSender {

    private final AliyunSmsProvider sms;
    private final WechatClient wechat;
    private final WechatProperties wechatProperties;

    ClientNotificationSender(AliyunSmsProvider sms, WechatClient wechat, WechatProperties wechatProperties) {
        this.sms = sms;
        this.wechat = wechat;
        this.wechatProperties = wechatProperties;
    }

    @Override
    public SmsResult sendSms(String phone, String content) {
        AliyunSmsProvider.SendSmsResult result = sms.sendSms(phone, Map.of("content", content));
        return new SmsResult(result.success(), result.message());
    }

    @Override
    public WechatSubscribe.@Nullable Template subscribeTemplate(String scene) {
        WechatProperties.SubscribeTemplate template =
                wechatProperties.subscribeTemplates().get(scene);
        return template == null ? null : new WechatSubscribe.Template(template.templateId(), template.fields());
    }

    @Override
    public void sendSubscribeMessage(String openId, WechatSubscribe.Message message) {
        try {
            wechat.sendSubscribeMessage(openId, message.templateId(), message.page(), message.data());
        } catch (WechatClient.ApiException e) {
            throw SubscribeFailure.ofErrcode(e.errcode(), String.valueOf(e.getMessage()));
        } catch (HttpStatusException e) {
            throw new SubscribeFailure(nestMessage(e), notConfigured(e), e);
        }
    }

    private static boolean notConfigured(HttpStatusException e) {
        return e.response() instanceof Map<?, ?> body
                && body.get("errors") instanceof Map<?, ?> errors
                && "notConfigured".equals(errors.get("wechat"));
    }

    /** nestjs {@code HttpException.message}：响应体的字符串 message，否则为异常类名的分词形式。 */
    static String nestMessage(HttpStatusException e) {
        Object response = e.response();
        if (response instanceof String text) {
            return text;
        }
        if (response instanceof Map<?, ?> body && body.get("message") instanceof String text) {
            return text;
        }
        return HttpStatusNames.description(e.status()) + " Exception";
    }
}
