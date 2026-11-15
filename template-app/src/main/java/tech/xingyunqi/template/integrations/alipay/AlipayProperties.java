package tech.xingyunqi.template.integrations.alipay;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code ALIPAY_APP_ID}。 */
@ConfigurationProperties("app.alipay")
public record AlipayProperties(@Nullable String appId) {

    public boolean configured() {
        return appId != null && !appId.isEmpty();
    }
}
