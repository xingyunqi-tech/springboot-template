package tech.xingyunqi.template.infrastructure.throttler;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code THROTTLE_ENABLED}（只有字符串 {@code false} 关闭）、{@code THROTTLE_TTL}（秒）、{@code THROTTLE_LIMIT}。 */
@Validated
@ConfigurationProperties("app.throttle")
public record ThrottlerProperties(
        String enabled, @Min(1) int ttl, @Min(1) int limit) {

    public boolean isEnabled() {
        return !"false".equals(enabled);
    }
}
