package tech.xingyunqi.template.cli;

import java.util.Arrays;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.xingyunqi.template.common.redis.RedisAutoConfiguration;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tech.xingyunqi.template.core.permissions.PermissionService;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SeedProperties.class, SchemaProperties.class})
class CliConfiguration {

    @Bean
    Seeder seeder(
            JdbcTemplate jdbc, PasswordHasher passwords, JsonMapper mapper, AppProperties app, SeedProperties seed) {
        return new Seeder(jdbc, passwords, mapper, app.isProduction(), seed.adminEmail(), seed.adminPassword());
    }

    @Bean
    CliCommands cliCommands(
            JdbcTemplate jdbc, PasswordHasher passwords, PermissionService permissions, Environment environment) {
        return new CliCommands(jdbc, passwords, permissions, RedisAutoConfiguration.redisEnabled(environment));
    }

    @Bean
    SmartInitializingSingleton externalSchemaCheck(
            Environment environment, SchemaProperties schema, JdbcTemplate jdbc) {
        return () -> {
            String role = AppRole.of(environment);
            if (schema.external() && ("api".equals(role) || "migrate".equals(role))) {
                SchemaCheck.verify(jdbc);
            }
        };
    }

    @Bean
    ApplicationRunner appRoleRunner(
            ConfigurableApplicationContext context,
            Seeder seeder,
            CliCommands commands,
            TransactionTemplate transactions) {
        return args -> {
            switch (AppRole.of(context.getEnvironment())) {
                case "migrate" -> exit(context, CliCommands.EXIT_OK);
                case "seed" -> {
                    transactions.executeWithoutResult(status -> seeder.seed());
                    exit(context, CliCommands.EXIT_OK);
                }
                case "create-superuser" ->
                    exit(context, commands.createSuperuser(Arrays.asList(args.getSourceArgs()), System.in));
                case "permissions-clear-cache" -> exit(context, commands.clearPermissionCache());
                default -> {}
            }
        };
    }

    private static void exit(ConfigurableApplicationContext context, int code) {
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
