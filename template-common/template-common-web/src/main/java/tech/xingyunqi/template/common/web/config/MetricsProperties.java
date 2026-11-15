package tech.xingyunqi.template.common.web.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code METRICS_ENABLED}（只有 {@code false} 关闭）、{@code METRICS_PATH}、{@code METRICS_TOKEN}（设置时至少 16 个字符）。 */
@ConfigurationProperties("app.metrics")
public record MetricsProperties(
        @Nullable String enabled,
        @Nullable String path,
        @Nullable String token) {

    static final int TOKEN_MIN_LENGTH = 16;
    static final String DEFAULT_PATH = "metrics";

    public MetricsProperties {
        if (token != null && !token.isEmpty() && token.length() < TOKEN_MIN_LENGTH) {
            throw new IllegalStateException("METRICS_TOKEN must be at least " + TOKEN_MIN_LENGTH + " characters");
        }
    }

    public boolean isEnabled() {
        return !"false".equals(enabled);
    }

    /** 不带 {@code API_PREFIX} 的指标路由，形如 {@code /metrics}。 */
    public String route() {
        String trimmed = path == null ? DEFAULT_PATH : path.replaceAll("^/+|/+$", "");
        return "/" + trimmed;
    }

    public @Nullable String configuredToken() {
        return token == null || token.isEmpty() ? null : token;
    }
}
