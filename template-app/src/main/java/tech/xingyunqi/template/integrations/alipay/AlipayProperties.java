package tech.xingyunqi.template.integrations.alipay;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ALIPAY_APP_ID}、{@code ALIPAY_PRIVATE_KEY}（PKCS#8，可不带 PEM 头尾）、{@code ALIPAY_GATEWAY}（未设置时按
 * {@code ALIPAY_SANDBOX} 取正式或沙箱网关）、{@code ALIPAY_SIGN_TYPE}（RSA2 / RSA）、{@code ALIPAY_CHARSET}。
 */
@ConfigurationProperties("app.alipay")
public record AlipayProperties(
        @Nullable String appId,
        @Nullable String privateKey,
        @Nullable String gateway,
        @Nullable String signType,
        @Nullable String charset,
        @Nullable String sandbox) {

    public static final String GATEWAY = "https://openapi.alipay.com/gateway.do";
    public static final String SANDBOX_GATEWAY = "https://openapi.alipaydev.com/gateway.do";

    public AlipayProperties {
        if (signType != null && !signType.isEmpty() && !"RSA2".equals(signType) && !"RSA".equals(signType)) {
            throw new IllegalArgumentException("ALIPAY_SIGN_TYPE must be RSA2 or RSA; got \"" + signType + "\"");
        }
    }

    public boolean configured() {
        return appId != null && !appId.isEmpty();
    }

    public String gatewayOrDefault() {
        if (gateway != null && !gateway.isEmpty()) {
            return gateway;
        }
        return "true".equals(sandbox) ? SANDBOX_GATEWAY : GATEWAY;
    }

    public String signTypeOrDefault() {
        return signType == null || signType.isEmpty() ? "RSA2" : signType;
    }

    public String charsetOrDefault() {
        return charset == null || charset.isEmpty() ? "utf-8" : charset;
    }
}
