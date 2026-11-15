package tech.xingyunqi.template.common.observability.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ObservabilityEnvironmentTest {

    static Map<String, Object> properties(String... pairs) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < pairs.length; i += 2) {
            environment.setProperty(pairs[i], pairs[i + 1]);
        }
        return ObservabilityEnvironment.properties(environment);
    }

    @Test
    @DisplayName("未设置时非生产为 debug 单行文本，生产为 info JSON")
    void defaultsFollowEnvironment() {
        assertThat(properties())
                .containsEntry("logging.level.tech.xingyunqi", "DEBUG")
                .containsEntry("logging.level.root", "INFO")
                .doesNotContainKey("logging.structured.format.console");
        assertThat(properties("app.env", "production"))
                .containsEntry("logging.level.tech.xingyunqi", "INFO")
                .containsEntry("logging.structured.format.console", "logstash");
    }

    @Test
    @DisplayName("LOG_LEVEL 映射到 Logback 级别，silent 关闭输出，非法值启动失败")
    void logLevels() {
        assertThat(properties("LOG_LEVEL", "fatal")).containsEntry("logging.level.root", "ERROR");
        assertThat(properties("LOG_LEVEL", "warn"))
                .containsEntry("logging.level.tech.xingyunqi", "WARN")
                .containsEntry("logging.level.root", "WARN");
        assertThat(properties("LOG_LEVEL", "trace"))
                .containsEntry("logging.level.tech.xingyunqi", "TRACE")
                .containsEntry("logging.level.root", "INFO");
        assertThat(properties("LOG_LEVEL", "silent")).containsEntry("logging.level.root", "OFF");
        assertThatThrownBy(() -> properties("LOG_LEVEL", "verbose"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOG_LEVEL");
    }

    @Test
    @DisplayName("LOG_PRETTY_PRINT 显式设置时只有 true 开启单行文本，空值视为未设置")
    void prettyPrint() {
        assertThat(properties("LOG_PRETTY_PRINT", "false")).containsKey("logging.structured.format.console");
        assertThat(properties("LOG_PRETTY_PRINT", "1")).containsKey("logging.structured.format.console");
        assertThat(properties("LOG_PRETTY_PRINT", "")).doesNotContainKey("logging.structured.format.console");
        assertThat(properties("app.env", "production", "LOG_PRETTY_PRINT", "true"))
                .doesNotContainKey("logging.structured.format.console");
    }

    @Test
    @DisplayName("SENTRY_DSN 设置时才启用 Sentry，environment 取 APP_ENV 且不发送 PII")
    void sentry() {
        assertThat(properties()).doesNotContainKey("sentry.dsn");
        assertThat(properties("SENTRY_DSN", " ")).doesNotContainKey("sentry.dsn");
        assertThat(properties("SENTRY_DSN", "https://key@sentry.example/1", "app.env", "test"))
                .containsEntry("sentry.dsn", "https://key@sentry.example/1")
                .containsEntry("sentry.environment", "test")
                .containsEntry("sentry.send-default-pii", "false");
    }
}
