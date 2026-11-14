package tech.xingyunqi.template.common.mybatis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code DATABASE_CONNECTION_TIMEOUT_MS}：从连接池获取连接的超时（毫秒），不低于 Hikari 的下限 {@value #MIN_CONNECTION_TIMEOUT_MS}。 */
@ConfigurationProperties("app.database")
public record DatabaseProperties(long connectionTimeoutMs) {

    public static final long MIN_CONNECTION_TIMEOUT_MS = 250;

    public DatabaseProperties {
        if (connectionTimeoutMs < MIN_CONNECTION_TIMEOUT_MS) {
            throw new IllegalArgumentException("DATABASE_CONNECTION_TIMEOUT_MS must be >= " + MIN_CONNECTION_TIMEOUT_MS
                    + " (got " + connectionTimeoutMs + ")");
        }
    }
}
