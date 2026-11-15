package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.common.redis.throttle.RateLimiter;
import tech.xingyunqi.template.support.TestDatabase;

/** 限流使用独立的 Redis 容器，暂停容器模拟命令超时。 */
@SpringBootTest(properties = {"REDIS_ENABLED=true", "app.throttle.enabled=true", "app.throttle.limit=2"})
@AutoConfigureMockMvc
@Import(ThrottleFallbackIT.DedicatedRedis.class)
class ThrottleFallbackIT {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(TestDatabase.REDIS_IMAGE)).withExposedPorts(6379);

    @TestConfiguration(proxyBeanMethods = false)
    static class DedicatedRedis {

        @Bean
        DynamicPropertyRegistrar dedicatedRedisProperties() {
            String database = TestDatabase.cloneDatabase();
            REDIS.start();
            return registry -> {
                registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl(database));
                registry.add(
                        "spring.datasource.username",
                        () -> TestDatabase.postgres().getUsername());
                registry.add(
                        "spring.datasource.password",
                        () -> TestDatabase.postgres().getPassword());
                registry.add("spring.data.redis.host", REDIS::getHost);
                registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    RateLimiter limiter;

    @Autowired
    AppMetrics metrics;

    @Autowired
    JdbcTemplate jdbc;

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    MockHttpServletResponse get(String remoteAddr, String forwardedFor) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/")
                        .header("X-Forwarded-For", forwardedFor)
                        .with(request -> {
                            request.setRemoteAddr(remoteAddr);
                            return request;
                        }))
                .andReturn()
                .getResponse();
    }

    static void pause(boolean paused) {
        var docker = DockerClientFactory.instance().client();
        if (paused) {
            docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        } else {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    List<Map<String, Object>> alerts() {
        return jdbc.queryForList(
                "select type, level::text as level from admin_alert where \"dedupKey\" = ?",
                RateLimiter.FALLBACK_ALERT_KEY);
    }

    @Test
    @DisplayName("Redis 超时时改用进程内计数并累加回落计数，首次降级告警一次；恢复后重新使用 Redis")
    void fallsBackToMemoryAndRecovers() throws Exception {
        double before = metrics.value("throttler_storage_fallback_total");
        assertThat(get("198.51.100.10", "203.0.113.1").getStatus()).isEqualTo(200);
        assertThat(limiter.degraded()).isFalse();

        pause(true);
        List<Integer> statuses;
        try {
            statuses = List.of(
                    get("198.51.100.20", "203.0.113.1").getStatus(),
                    get("198.51.100.20", "203.0.113.2").getStatus(),
                    get("198.51.100.20", "203.0.113.3").getStatus());
        } finally {
            pause(false);
        }
        assertThat(statuses).containsExactly(200, 200, 429);
        assertThat(limiter.degraded()).isTrue();
        assertThat(metrics.value("throttler_storage_fallback_total")).isEqualTo(before + 3);
        long deadline = System.currentTimeMillis() + 10_000;
        while (alerts().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(alerts()).containsExactly(Map.of("type", "throttler", "level", "CRITICAL"));

        MockHttpServletResponse recovered = get("198.51.100.30", "203.0.113.4");
        assertThat(recovered.getStatus()).isEqualTo(200);
        assertThat(recovered.getHeader("X-RateLimit-Remaining")).isEqualTo("1");
        assertThat(limiter.degraded()).isFalse();
        assertThat(metrics.value("throttler_storage_fallback_total")).isEqualTo(before + 3);
        assertThat(alerts()).hasSize(1);
    }
}
