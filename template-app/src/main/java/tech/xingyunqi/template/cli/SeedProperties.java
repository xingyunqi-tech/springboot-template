package tech.xingyunqi.template.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 生产环境 seed 的管理员账号（{@code SEED_ADMIN_EMAIL} / {@code SEED_ADMIN_PASSWORD}）。 */
@ConfigurationProperties("app.seed")
public record SeedProperties(
        @Nullable String adminEmail, @Nullable String adminPassword) {}
