package tech.xingyunqi.template.common.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DatabasePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DatabaseProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @ParameterizedTest
    @ValueSource(strings = {"0", "100", "249", "-1"})
    @DisplayName("连接超时低于 250 毫秒时启动失败")
    void rejectsTimeoutBelowHikariMinimum(String value) {
        runner.withPropertyValues("app.database.connection-timeout-ms=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("DATABASE_CONNECTION_TIMEOUT_MS must be >= 250");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"250", "10000"})
    @DisplayName("连接超时不低于 250 毫秒时正常绑定")
    void acceptsTimeoutAtOrAboveMinimum(String value) {
        runner.withPropertyValues("app.database.connection-timeout-ms=" + value)
                .run(context -> assertThat(
                                context.getBean(DatabaseProperties.class).connectionTimeoutMs())
                        .isEqualTo(Long.parseLong(value)));
    }
}
