package tech.xingyunqi.template.common.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;

/**
 * 带过期时间的互斥占位（nestjs {@code LockService}）：Redis 键 {@code lock:<key>}、值为随机 token，{@code SET NX PX} 占用， Lua 比较 token
 * 后删除；Redis 未启用或命令失败（单条命令超时 1s）时回落进程内锁，仅本实例生效。
 */
public final class LockService implements DisposableBean {

    public static final String KEY_PREFIX = "lock:";
    public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(1);
    static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private static final Logger LOG = LoggerFactory.getLogger(LockService.class);

    private record LocalLock(String token, long expiresAtNanos) {}

    private final @Nullable StringRedisTemplate redis;
    private final @Nullable AutoCloseable connection;
    private final LongSupplier nanoTime;
    private final Map<String, LocalLock> local = new ConcurrentHashMap<>();

    public LockService(@Nullable StringRedisTemplate redis, @Nullable AutoCloseable connection) {
        this(redis, connection, System::nanoTime);
    }

    LockService(@Nullable StringRedisTemplate redis, @Nullable AutoCloseable connection, LongSupplier nanoTime) {
        this.redis = redis;
        this.connection = connection;
        this.nanoTime = nanoTime;
    }

    /** 进程内锁，用于 {@code REDIS_ENABLED=false}。 */
    public static LockService local() {
        return new LockService(null, null);
    }

    /** 在 {@code ttlMs} 内独占 {@code key}，成功时返回释放用的 token，已被占用时返回 null。 */
    public @Nullable String tryAcquire(String key, long ttlMs) {
        String token = UUID.randomUUID().toString();
        if (redis != null) {
            try {
                byte[] redisKey = (KEY_PREFIX + key).getBytes(StandardCharsets.UTF_8);
                byte[] value = token.getBytes(StandardCharsets.UTF_8);
                Boolean acquired = redis.execute((RedisCallback<Boolean>) commands -> commands.stringCommands()
                        .set(redisKey, value, Expiration.milliseconds(ttlMs), SetOption.ifAbsent()));
                return Boolean.TRUE.equals(acquired) ? token : null;
            } catch (RuntimeException e) {
                LOG.warn("Redis lock unavailable, using local lock for {}: {}", key, e.getMessage());
            }
        }
        return tryAcquireLocally(key, token, ttlMs) ? token : null;
    }

    /** 仍由 {@code token} 持有时释放 {@code key}。 */
    public void release(String key, String token) {
        local.computeIfPresent(key, (ignored, held) -> held.token().equals(token) ? null : held);
        if (redis != null) {
            try {
                redis.execute(RELEASE_SCRIPT, List.of(KEY_PREFIX + key), token);
            } catch (RuntimeException e) {
                LOG.warn("Failed to release lock {}: {}", key, e.getMessage());
            }
        }
    }

    private boolean tryAcquireLocally(String key, String token, long ttlMs) {
        long now = nanoTime.getAsLong();
        LocalLock candidate =
                new LocalLock(token, now + Duration.ofMillis(ttlMs).toNanos());
        LocalLock held = local.compute(
                key,
                (ignored, current) -> current == null || current.expiresAtNanos() - now <= 0 ? candidate : current);
        return held == candidate;
    }

    @Override
    public void destroy() throws Exception {
        local.clear();
        if (connection != null) {
            connection.close();
        }
    }
}
