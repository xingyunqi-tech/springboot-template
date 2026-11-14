package tech.xingyunqi.template.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** 当前上下文使用一个从模板库克隆的独立数据库，以及共用的 Redis 容器。 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    DynamicPropertyRegistrar testDatabaseProperties() {
        String database = TestDatabase.cloneDatabase();
        return registry -> {
            registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl(database));
            registry.add(
                    "spring.datasource.username", () -> TestDatabase.postgres().getUsername());
            registry.add(
                    "spring.datasource.password", () -> TestDatabase.postgres().getPassword());
            registry.add("spring.data.redis.host", () -> TestDatabase.redis().getHost());
            registry.add("spring.data.redis.port", () -> TestDatabase.redis().getMappedPort(6379));
        };
    }
}
