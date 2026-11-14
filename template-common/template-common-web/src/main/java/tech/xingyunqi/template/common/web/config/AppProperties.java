package tech.xingyunqi.template.common.web.config;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 应用级配置：APP_ENV、APP_NAME、API_PREFIX、FRONTEND_DOMAIN、BACKEND_DOMAIN、APP_FALLBACK_LANGUAGE、SWAGGER_ENABLED、TRUST_PROXY（反向代理层数）。
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(
        Environment env,
        @NotBlank String name,
        @NotBlank String apiPrefix,
        @Nullable String frontendDomain,
        @NotBlank String backendDomain,
        @NotBlank String fallbackLanguage,
        @Nullable Boolean swaggerEnabled,
        int trustProxy) {

    public enum Environment {
        DEVELOPMENT,
        TEST,
        PRODUCTION
    }

    public boolean isProduction() {
        return env == Environment.PRODUCTION;
    }

    /** 显式设置时按其取值，未设置时非生产开启。 */
    public boolean docsEnabled() {
        return swaggerEnabled != null ? swaggerEnabled : !isProduction();
    }
}
