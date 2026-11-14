package tech.xingyunqi.template.cli;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.xingyunqi.template.common.security.password.PasswordHasher;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SeedProperties.class)
class CliConfiguration {

    @Bean
    Seeder seeder(
            JdbcTemplate jdbc, PasswordHasher passwords, JsonMapper mapper, AppProperties app, SeedProperties seed) {
        return new Seeder(jdbc, passwords, mapper, app.isProduction(), seed.adminEmail(), seed.adminPassword());
    }

    @Bean
    ApplicationRunner appRoleRunner(
            ConfigurableApplicationContext context, Seeder seeder, TransactionTemplate transactions) {
        return args -> {
            switch (AppRole.of(context.getEnvironment())) {
                case "migrate" -> System.exit(SpringApplication.exit(context, () -> 0));
                case "seed" -> {
                    transactions.executeWithoutResult(status -> seeder.seed());
                    System.exit(SpringApplication.exit(context, () -> 0));
                }
                default -> {}
            }
        };
    }
}
