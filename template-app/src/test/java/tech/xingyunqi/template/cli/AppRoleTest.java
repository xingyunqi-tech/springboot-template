package tech.xingyunqi.template.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class AppRoleTest {

    static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new AppRole().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    @DisplayName("worker 不启动 Web，运行 background server，连接池取 WORKER_DATABASE_MAX_CONNECTIONS")
    void workerRunsBackgroundServer() {
        StandardEnvironment env = environment(Map.of("APP_ROLE", "worker", "WORKER_DATABASE_MAX_CONNECTIONS", "7"));
        assertThat(env.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(env.getProperty(AppRole.BACKGROUND_SERVER)).isEqualTo("true");
        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size"))
                .isEqualTo("7");
    }

    @Test
    @DisplayName("api 未设置 JOBS_IN_PROCESS 时只在非生产运行 background server")
    void apiRunsJobsInProcessOutsideProduction() {
        assertThat(environment(Map.of("app.env", "development")).getProperty(AppRole.BACKGROUND_SERVER))
                .isEqualTo("true");
        assertThat(environment(Map.of("app.env", "production")).getProperty(AppRole.BACKGROUND_SERVER))
                .isEqualTo("false");
        assertThat(environment(Map.of("app.env", "development", "JOBS_IN_PROCESS", "false"))
                        .getProperty(AppRole.BACKGROUND_SERVER))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("显式配置的 background server 开关优先于 api 的默认值")
    void explicitSettingWins() {
        assertThat(environment(Map.of("app.env", "development", AppRole.BACKGROUND_SERVER, "false"))
                        .getProperty(AppRole.BACKGROUND_SERVER))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("migrate 执行 Flyway 与 JobRunr 建表，不运行 background server")
    void migrateCreatesJobTables() {
        StandardEnvironment env = environment(Map.of("APP_ROLE", "migrate"));
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(env.getProperty("jobrunr.database.skip-create")).isEqualTo("false");
        assertThat(env.getProperty(AppRole.BACKGROUND_SERVER)).isEqualTo("false");
    }
}
