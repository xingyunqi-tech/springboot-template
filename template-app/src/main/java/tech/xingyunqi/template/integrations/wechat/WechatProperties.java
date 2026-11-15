package tech.xingyunqi.template.integrations.wechat;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code WECHAT_APP_ID} / {@code WECHAT_APP_SECRET}。 */
@ConfigurationProperties("app.wechat")
public record WechatProperties(
        @Nullable String appId, @Nullable String appSecret) {

    public boolean configured() {
        return appId != null && !appId.isEmpty() && appSecret != null && !appSecret.isEmpty();
    }
}
