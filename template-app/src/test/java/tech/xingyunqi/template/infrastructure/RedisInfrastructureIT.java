package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.common.redis.LockService;
import tech.xingyunqi.template.common.redis.RedisAutoConfiguration;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"REDIS_ENABLED=true", "HEALTH_MEMORY_HEAP_THRESHOLD_MB=100000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RedisInfrastructureIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    LockService locks;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    DataRedisConnectionDetails details;

    @Test
    @DisplayName("锁键为 lock:<key>、值为 token，两个实例并发争用只有一个成功；只有持有者能释放")
    void onlyOneInstanceAcquires() throws Exception {
        String key = "it-" + UUID.randomUUID();
        LettuceConnectionFactory factory =
                RedisAutoConfiguration.connectionFactory(details, LockService.COMMAND_TIMEOUT);
        LockService other = new LockService(new StringRedisTemplate(factory), factory::destroy);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<String>> attempts = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                LockService instance = i % 2 == 0 ? locks : other;
                attempts.add(() -> instance.tryAcquire(key, 60_000));
            }
            List<String> winners = new ArrayList<>();
            for (Future<String> attempt : executor.invokeAll(attempts)) {
                if (attempt.get() != null) {
                    winners.add(attempt.get());
                }
            }
            assertThat(winners).hasSize(1);
            assertThat(redis.opsForValue().get("lock:" + key)).isEqualTo(winners.getFirst());
            assertThat(redis.getExpire("lock:" + key, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isBetween(1L, 60_000L);

            other.release(key, "not-the-owner");
            assertThat(redis.hasKey("lock:" + key)).isTrue();
            other.release(key, winners.getFirst());
            assertThat(redis.hasKey("lock:" + key)).isFalse();
        } finally {
            other.destroy();
        }
    }

    @Test
    @DisplayName("REDIS_ENABLED=true 时完整检查在第 5 项加入 redis")
    void healthIncludesRedis() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/health")).andReturn().getResponse();
        JsonNode details = JsonMapper.builder()
                .build()
                .readTree(response.getContentAsString())
                .path("data")
                .path("details");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(List.copyOf(details.propertyNames()).indexOf("redis")).isEqualTo(4);
        assertThat(details.path("redis").toString()).isEqualTo("{\"status\":\"up\"}");
    }
}
