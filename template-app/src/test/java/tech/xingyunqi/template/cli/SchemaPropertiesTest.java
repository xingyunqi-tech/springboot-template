package tech.xingyunqi.template.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SchemaPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SchemaProperties.class)
    static class Config {}

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    @DisplayName("DB_SCHEMA_OWNER 只接受 flyway 与 external")
    void acceptsKnownOwners() {
        runner.withPropertyValues("app.schema.owner=flyway")
                .run(context -> assertThat(
                                context.getBean(SchemaProperties.class).external())
                        .isFalse());
        runner.withPropertyValues("app.schema.owner=external")
                .run(context -> assertThat(
                                context.getBean(SchemaProperties.class).external())
                        .isTrue());
        runner.withPropertyValues("app.schema.owner=typeorm")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("所需列取自 nestjs 表结构快照")
    void requiredColumnsFromSnapshot() {
        assertThat(SchemaCheck.requiredColumns())
                .hasSize(153)
                .contains("user.email", "account_cleanup.userId", "admin_alert.dedupKey");
    }
}
