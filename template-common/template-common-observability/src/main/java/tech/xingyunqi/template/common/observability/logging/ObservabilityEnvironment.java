package tech.xingyunqi.template.common.observability.logging;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 日志与 Sentry 的环境变量：{@code LOG_LEVEL}（未设置时生产 info、其它 debug；作用于应用日志，框架日志不低于 info）、 {@code LOG_PRETTY_PRINT}（未设置时非生产为
 * true；false 时输出 logstash JSON）、{@code SENTRY_DSN}（设置时才启用 Sentry）。
 */
public final class ObservabilityEnvironment implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE = "observability";
    static final String APP_LOGGER = "tech.xingyunqi";
    static final Map<String, String> LEVELS = Map.of(
            "fatal", "ERROR",
            "error", "ERROR",
            "warn", "WARN",
            "info", "INFO",
            "debug", "DEBUG",
            "trace", "TRACE",
            "silent", "OFF");

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, properties(environment)));
    }

    static Map<String, Object> properties(ConfigurableEnvironment environment) {
        boolean production = "production".equalsIgnoreCase(environment.getProperty("app.env", "development"));
        Map<String, Object> properties = new LinkedHashMap<>();
        String level = logbackLevel(environment.getProperty("LOG_LEVEL"), production);
        properties.put("logging.level." + APP_LOGGER, level);
        properties.put("logging.level.root", frameworkLevel(level));
        String pretty = environment.getProperty("LOG_PRETTY_PRINT");
        if (!(pretty != null && !pretty.isEmpty() ? "true".equals(pretty) : !production)) {
            properties.put("logging.structured.format.console", "logstash");
        }
        String dsn = environment.getProperty("SENTRY_DSN");
        if (dsn != null && !dsn.isBlank()) {
            properties.put("sentry.dsn", dsn);
            properties.put("sentry.environment", environment.getProperty("app.env", "development"));
            properties.put("sentry.send-default-pii", "false");
        }
        return properties;
    }

    static String logbackLevel(@Nullable String logLevel, boolean production) {
        if (logLevel == null || logLevel.isEmpty()) {
            return production ? "INFO" : "DEBUG";
        }
        String level = LEVELS.get(logLevel);
        if (level == null) {
            throw new IllegalStateException(
                    "LOG_LEVEL must be one of fatal, error, warn, info, debug, trace, silent; got " + logLevel);
        }
        return level;
    }

    static String frameworkLevel(String appLevel) {
        return switch (appLevel.toUpperCase(Locale.ROOT)) {
            case "DEBUG", "TRACE" -> "INFO";
            default -> appLevel;
        };
    }
}
