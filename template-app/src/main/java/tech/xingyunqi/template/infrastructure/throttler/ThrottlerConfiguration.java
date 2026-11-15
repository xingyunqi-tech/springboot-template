package tech.xingyunqi.template.infrastructure.throttler;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tech.xingyunqi.template.common.oss.FileStorageProperties;
import tech.xingyunqi.template.common.redis.throttle.RateLimiter;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tech.xingyunqi.template.common.security.route.AuthProperties;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ThrottlerProperties.class)
class ThrottlerConfiguration {

    static final String NEST_HANDLERS_RESOURCE = "openapi/nest_handlers.json";

    @Bean
    ThrottleGuard throttleGuard(
            ThrottlerProperties properties,
            RateLimiter limiter,
            Jwt jwt,
            AuthProperties auth,
            AppProperties app,
            JsonMapper mapper,
            FileStorageProperties files) {
        return new ThrottleGuard(
                properties, limiter, jwt, auth.secret(), app.trustProxy(), nestHandlers(mapper), files.fileDriver());
    }

    @Bean
    WebMvcConfigurer throttleWebMvcConfigurer(ThrottleGuard guard) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(guard).order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }

    static Map<String, String> nestHandlers(JsonMapper mapper) {
        try (InputStream input = new ClassPathResource(NEST_HANDLERS_RESOURCE).getInputStream()) {
            Map<String, String> handlers = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : mapper.readTree(input).properties()) {
                handlers.put(entry.getKey(), entry.getValue().stringValue());
            }
            return handlers;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
