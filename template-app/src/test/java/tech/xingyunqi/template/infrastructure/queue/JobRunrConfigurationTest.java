package tech.xingyunqi.template.infrastructure.queue;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JobRunrConfigurationTest {

    @Test
    @DisplayName("生产环境的 api 进程开启 background server 时启动失败，worker 不受限")
    void productionApiMustNotRunJobs() {
        assertThatThrownBy(() -> JobRunrConfiguration.checkInProcessAllowed(
                        new MockEnvironment().withProperty("app.env", "production")))
                .hasMessageContaining("JOBS_IN_PROCESS");
        assertThatCode(() -> JobRunrConfiguration.checkInProcessAllowed(new MockEnvironment()
                        .withProperty("app.env", "production")
                        .withProperty("APP_ROLE", "worker")))
                .doesNotThrowAnyException();
        assertThatCode(() -> JobRunrConfiguration.checkInProcessAllowed(
                        new MockEnvironment().withProperty("app.env", "development")))
                .doesNotThrowAnyException();
    }
}
