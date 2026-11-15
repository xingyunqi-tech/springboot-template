package tech.xingyunqi.template.cli;

import java.util.Locale;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * {@code APP_ROLE}：{@code api}（默认）启动 Web；{@code worker} 不启动 Web，运行 JobRunr background server（含 recurring job），连接池上限为
 * {@code WORKER_DATABASE_MAX_CONNECTIONS}；{@code migrate} 跑 Flyway 与 JobRunr 建表后退出；{@code seed} 写入种子数据后退出。{@code api}
 * 只在 {@code JOBS_IN_PROCESS} 为 {@code true}（未设置时非生产视为 {@code true}）时在进程内运行 background server。
 */
public final class AppRole implements EnvironmentPostProcessor {

    public static final String PROPERTY = "APP_ROLE";
    public static final String JOBS_IN_PROCESS = "JOBS_IN_PROCESS";
    public static final String BACKGROUND_SERVER = "jobrunr.background-job-server.enabled";

    public static String of(Environment environment) {
        return environment.getProperty(PROPERTY, "api").strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String role = of(environment);
        Map<String, Object> overrides = switch (role) {
            case "migrate" ->
                Map.of(
                        "spring.main.web-application-type",
                        "none",
                        "spring.flyway.enabled",
                        "true",
                        "jobrunr.database.skip-create",
                        "false",
                        BACKGROUND_SERVER,
                        "false");
            case "seed" -> Map.of("spring.main.web-application-type", "none", BACKGROUND_SERVER, "false");
            case "worker" ->
                Map.of(
                        "spring.main.web-application-type",
                        "none",
                        "spring.main.keep-alive",
                        "true",
                        BACKGROUND_SERVER,
                        "true",
                        "spring.datasource.hikari.maximum-pool-size",
                        "${WORKER_DATABASE_MAX_CONNECTIONS:10}");
            case "api" -> Map.of();
            default -> throw new IllegalStateException("unknown APP_ROLE: " + role);
        };
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("appRole", overrides));
        }
        if ("api".equals(role)) {
            String inProcess = environment.getProperty(JOBS_IN_PROCESS);
            boolean enabled = inProcess == null || inProcess.isBlank()
                    ? !"production".equalsIgnoreCase(environment.getProperty("app.env"))
                    : "true".equals(inProcess);
            environment
                    .getPropertySources()
                    .addLast(new MapPropertySource("appRoleDefaults", Map.of(BACKGROUND_SERVER, enabled)));
        }
    }
}
