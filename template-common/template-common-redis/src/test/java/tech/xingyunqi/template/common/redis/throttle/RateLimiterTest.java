package tech.xingyunqi.template.common.redis.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tech.xingyunqi.template.common.core.alert.AlertLevel;
import tech.xingyunqi.template.common.core.alert.AlertReporter;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.common.redis.RedisAutoConfiguration;

class RateLimiterTest {

    static final long MINUTE = 60_000_000L;

    record Raised(
            String type,
            AlertLevel level,
            String message,
            @Nullable Map<String, ?> payload,
            String dedupKey) {}

    @Test
    @DisplayName("键为 nestjs 计数键 throttle:<sha256>:default 加 :gcra 后缀")
    void keyMatchesNestjs() {
        assertThat(RateLimiter.key("HomeController-appInfo", "ip:203.0.113.7"))
                .isEqualTo("throttle:cec3b8362cf5aa1b3881c7f7a8f57f7beae1de77d154b0e88480034dddf7dc22:default:gcra");
        assertThat(RateLimiter.key("AuthController-update", "user:42"))
                .isEqualTo("throttle:bb91c6d29e1ca3b376e4eb6e84727be0329093a85fdaa7047f2837a921c3c4bf:default:gcra");
    }

    @Test
    @DisplayName("Redis 未启用时直接用进程内计数，不计回落")
    void memoryOnlyWithoutRedis() throws Exception {
        AppMetrics metrics = new AppMetrics("app");
        AtomicLong now = new AtomicLong(GcraTest.FIXED_NOW);
        RateLimiter limiter = new RateLimiter(null, null, metrics, () -> null, now::get);
        try {
            assertThat(limiter.consume("k", MINUTE, 1).allowed()).isTrue();
            assertThat(limiter.consume("k", MINUTE, 1).allowed()).isFalse();
            now.addAndGet(MINUTE);
            assertThat(limiter.consume("k", MINUTE, 1).allowed()).isTrue();
            assertThat(metrics.value("throttler_storage_fallback_total")).isZero();
            assertThat(limiter.degraded()).isFalse();
        } finally {
            limiter.destroy();
        }
    }

    @Test
    @DisplayName("Redis 出错时每次回落都计数，只在首次降级时告警")
    void fallsBackAndAlertsOnce() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        int closedPort = port;
        LettuceConnectionFactory factory = RedisAutoConfiguration.connectionFactory(
                new DataRedisConnectionDetails() {
                    @Override
                    public Standalone getStandalone() {
                        return Standalone.of("127.0.0.1", closedPort);
                    }
                },
                RateLimiter.COMMAND_TIMEOUT);
        AppMetrics metrics = new AppMetrics("app");
        List<Raised> raised = new CopyOnWriteArrayList<>();
        AlertReporter reporter = (type, level, message, payload, dedupKey) -> {
            raised.add(new Raised(type, level, message, payload, String.valueOf(dedupKey)));
            return 1;
        };
        RateLimiter limiter =
                new RateLimiter(new StringRedisTemplate(factory), factory::destroy, metrics, () -> reporter, () -> 0L);
        try {
            assertThat(limiter.consume("k", MINUTE, 2).allowed()).isTrue();
            assertThat(limiter.consume("k", MINUTE, 2).allowed()).isTrue();
            assertThat(limiter.consume("k", MINUTE, 2).allowed()).isFalse();
            assertThat(limiter.degraded()).isTrue();
            assertThat(metrics.value("throttler_storage_fallback_total")).isEqualTo(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (raised.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(raised).hasSize(1);
            Raised alert = raised.getFirst();
            assertThat(alert.type()).isEqualTo("throttler");
            assertThat(alert.level()).isEqualTo(AlertLevel.CRITICAL);
            assertThat(alert.dedupKey()).isEqualTo("throttler-storage-fallback");
            assertThat(alert.message()).startsWith("限流存储 Redis 不可用，已降级为单实例内存计数：");
            assertThat(alert.payload()).containsKey("reason");
        } finally {
            limiter.destroy();
        }
    }
}
