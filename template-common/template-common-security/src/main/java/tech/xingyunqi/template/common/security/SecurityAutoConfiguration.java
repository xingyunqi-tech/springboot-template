package tech.xingyunqi.template.common.security;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tech.xingyunqi.template.common.security.jwt.Ms;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tech.xingyunqi.template.common.security.route.AccessTokenValidator;
import tech.xingyunqi.template.common.security.route.AuthProperties;
import tech.xingyunqi.template.common.security.route.PermissionResolver;
import tech.xingyunqi.template.common.security.route.RouteAuth;
import tech.xingyunqi.template.common.security.route.RouteAuthInterceptor;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tools.jackson.databind.json.JsonMapper;

/** JWT、密码哈希、路由鉴权拦截器；启动时校验密钥与每个 handler 的鉴权声明。 */
@AutoConfiguration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityAutoConfiguration {

    private static final int PRODUCTION_SECRET_MIN_LENGTH = 32;

    @Bean
    Jwt jwt(JsonMapper mapper, Clock clock) {
        return new Jwt(mapper, clock);
    }

    @Bean
    PasswordHasher passwordHasher(AuthProperties properties) {
        return new PasswordHasher(
                PasswordHasher.Algorithm.parse(properties.passwordHasher()), properties.passwordHashConcurrency());
    }

    @Bean
    RouteAuthInterceptor routeAuthInterceptor(
            Jwt jwt, AuthProperties properties, AccessTokenValidator validator, PermissionResolver permissions) {
        return new RouteAuthInterceptor(jwt, properties, validator, permissions);
    }

    @Bean
    WebMvcConfigurer routeAuthWebMvcConfigurer(RouteAuthInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    @Bean
    static SmartInitializingSingleton routeAuthVerifier(
            ObjectProvider<RequestMappingHandlerMapping> mappings,
            ObjectProvider<AuthProperties> properties,
            ObjectProvider<AppProperties> app) {
        return () -> {
            verifySecrets(properties.getObject(), app.getObject().isProduction());
            mappings.orderedStream()
                    .forEach(mapping -> mapping.getHandlerMethods().values().forEach(RouteAuth::of));
        };
    }

    static void verifySecrets(AuthProperties properties, boolean production) {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("AUTH_JWT_SECRET", properties.secret());
        secrets.put("AUTH_REFRESH_SECRET", properties.refreshSecret());
        secrets.put("AUTH_FORGOT_SECRET", properties.forgotSecret());
        secrets.put("AUTH_CONFIRM_EMAIL_SECRET", properties.confirmEmailSecret());
        List<String> problems = new ArrayList<>();
        secrets.forEach((name, value) -> {
            if (value == null || value.isEmpty()) {
                problems.add(name + " is required");
            } else if (production && value.length() < PRODUCTION_SECRET_MIN_LENGTH) {
                problems.add(name + " must be at least " + PRODUCTION_SECRET_MIN_LENGTH + " characters in production");
            }
        });
        if (production && secrets.values().stream().distinct().count() < secrets.size()) {
            problems.add("AUTH_*_SECRET values must be distinct in production");
        }
        for (String expires : new String[] {
            properties.expires(),
            properties.refreshExpires(),
            properties.forgotExpires(),
            properties.confirmEmailExpires()
        }) {
            try {
                Ms.parse(expires);
            } catch (IllegalArgumentException | NullPointerException e) {
                problems.add("invalid token lifetime: " + expires);
            }
        }
        if (properties.passwordHashConcurrency() < 1) {
            problems.add("PASSWORD_HASH_CONCURRENCY must be >= 1");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("invalid auth configuration: " + String.join("; ", problems));
        }
    }
}
