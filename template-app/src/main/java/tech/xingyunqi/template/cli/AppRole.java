package tech.xingyunqi.template.cli;

import java.util.Locale;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** {@code APP_ROLE}：{@code api}（默认）启动 Web；{@code migrate} 只跑 Flyway 后退出；{@code seed} 写入种子数据后退出。后两者不启动 Web 服务器。 */
public final class AppRole implements EnvironmentPostProcessor {

    public static final String PROPERTY = "APP_ROLE";

    public static String of(ConfigurableEnvironment environment) {
        return environment.getProperty(PROPERTY, "api").strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String role = of(environment);
        Map<String, Object> overrides = switch (role) {
            case "migrate" -> Map.of("spring.main.web-application-type", "none", "spring.flyway.enabled", "true");
            case "seed" -> Map.of("spring.main.web-application-type", "none");
            case "api" -> Map.of();
            default -> throw new IllegalStateException("unknown APP_ROLE: " + role);
        };
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("appRole", overrides));
        }
    }
}
