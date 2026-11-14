package tech.xingyunqi.template.common.security.route;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code AUTH_*} 令牌密钥与有效期，以及密码哈希配置。 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
        String secret,
        String expires,
        String refreshSecret,
        String refreshExpires,
        String forgotSecret,
        String forgotExpires,
        String confirmEmailSecret,
        String confirmEmailExpires,
        String passwordHasher,
        int passwordHashConcurrency) {}
