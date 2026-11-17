package tech.xingyunqi.template.modules.notifications;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.modules.notifications.domain.WechatSubscribe;

/** 通知队列的站外发送：短信与微信订阅消息。 */
public interface NotificationSender {

    /** nestjs {@code AliyunSmsProvider.sendSms} 的结果。 */
    record SmsResult(boolean success, @Nullable String message) {}

    /** 订阅消息发送失败；{@code unrecoverable} 时队列不重试。 */
    final class SubscribeFailure extends RuntimeException {

        /** 43101 用户未订阅或次数已用完，40003 openid 无效，40037 模板 ID 无效，41030 page 无效，47003 模板参数不合法。 */
        public static final Set<Integer> UNRECOVERABLE_ERROR_CODES = Set.of(43101, 40003, 40037, 41030, 47003);

        private final boolean unrecoverable;

        public SubscribeFailure(String message, boolean unrecoverable, @Nullable Throwable cause) {
            super(message, cause);
            this.unrecoverable = unrecoverable;
        }

        public static SubscribeFailure ofErrcode(int errcode, String message) {
            return new SubscribeFailure(message, UNRECOVERABLE_ERROR_CODES.contains(errcode), null);
        }

        public boolean unrecoverable() {
            return unrecoverable;
        }
    }

    /** 以 {@code {content}} 为模板参数发送短信。 */
    SmsResult sendSms(String phone, String content);

    /** {@code wechat.subscribeTemplates[scene]}；未配置时为 null。 */
    WechatSubscribe.@Nullable Template subscribeTemplate(String scene);

    /** 失败时抛出异常（不可重试的失败为 {@link SubscribeFailure}）。 */
    void sendSubscribeMessage(String openId, WechatSubscribe.Message message);
}
