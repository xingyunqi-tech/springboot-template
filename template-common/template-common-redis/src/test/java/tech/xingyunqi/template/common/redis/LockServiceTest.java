package tech.xingyunqi.template.common.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class LockServiceTest {

    @Test
    @DisplayName("进程内锁在释放前独占，只有持有者的 token 能释放")
    void localLockIsExclusiveUntilReleased() {
        LockService locks = LockService.local();
        String token = locks.tryAcquire("job", 60_000);
        assertThat(token).isNotNull();
        assertThat(locks.tryAcquire("job", 60_000)).isNull();
        assertThat(locks.tryAcquire("other", 60_000)).isNotNull();

        locks.release("job", "not-the-owner");
        assertThat(locks.tryAcquire("job", 60_000)).isNull();
        locks.release("job", token);
        assertThat(locks.tryAcquire("job", 60_000)).isNotNull();
    }

    @Test
    @DisplayName("进程内锁过期后可再次占用")
    void localLockExpiresAfterTtl() {
        AtomicLong now = new AtomicLong();
        LockService locks = new LockService(null, null, now::get);
        assertThat(locks.tryAcquire("job", 100)).isNotNull();
        now.addAndGet(99_000_000);
        assertThat(locks.tryAcquire("job", 100)).isNull();
        now.addAndGet(1_000_000);
        assertThat(locks.tryAcquire("job", 100)).isNotNull();
    }

    @Test
    @DisplayName("Redis 不可用时回落进程内锁")
    void fallsBackToLocalLockWhenRedisIsDown() throws Exception {
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
                LockService.COMMAND_TIMEOUT);
        LockService locks = new LockService(new StringRedisTemplate(factory), factory::destroy);
        try {
            String token = locks.tryAcquire("job", 60_000);
            assertThat(token).isNotNull();
            assertThat(locks.tryAcquire("job", 60_000)).isNull();
            locks.release("job", token);
            assertThat(locks.tryAcquire("job", 60_000)).isNotNull();
        } finally {
            locks.destroy();
        }
    }
}
