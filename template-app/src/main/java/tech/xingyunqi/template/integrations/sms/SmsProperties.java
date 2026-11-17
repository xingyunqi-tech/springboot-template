package tech.xingyunqi.template.integrations.sms;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code SMS_*}：阿里云短信凭证、签名与模板，验证码有效期（秒）、位数、最大尝试次数与重发间隔（秒）；{@code SMS_MOCK_MODE} 只认字符串 {@code true}。 */
@Validated
@ConfigurationProperties("app.sms")
public record SmsProperties(
        @Nullable String accessKeyId,
        @Nullable String accessKeySecret,
        @Nullable String signName,
        @Nullable String templateCode,
        @Min(60) @Max(600) int codeExpires,
        @Min(4) @Max(8) int codeLength,
        @Nullable String endpoint,
        @Nullable String mockMode,
        @Min(1) @Max(10) int maxAttempts,
        @Min(30) @Max(300) int resendInterval) {

    public static final String DEFAULT_ENDPOINT = "dysmsapi.aliyuncs.com";

    public boolean mock() {
        return "true".equals(mockMode);
    }

    public boolean hasCredentials() {
        return accessKeyId != null && !accessKeyId.isEmpty() && accessKeySecret != null && !accessKeySecret.isEmpty();
    }

    public String endpointOrDefault() {
        return endpoint == null || endpoint.isEmpty() ? DEFAULT_ENDPOINT : endpoint;
    }
}
