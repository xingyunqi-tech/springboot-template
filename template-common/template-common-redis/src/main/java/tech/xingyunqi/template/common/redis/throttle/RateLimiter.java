package tech.xingyunqi.template.common.redis.throttle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tech.xingyunqi.template.common.core.alert.AlertLevel;
import tech.xingyunqi.template.common.core.alert.AlertReporter;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;

/**
 * GCRA 限流存储（nestjs {@code RedisThrottlerStorage}）：Redis Lua 原子判定，单条命令超时 500ms；Redis 未启用或出错时改用进程内计数， 每次回落累加
 * {@code throttler_storage_fallback_total}，首次降级记 error 日志并告警，恢复时记 info 日志。
 */
public final class RateLimiter implements DisposableBean {

    public static final Duration COMMAND_TIMEOUT = Duration.ofMillis(500);
    public static final String THROTTLER_NAME = "default";
    public static final String FALLBACK_ALERT_KEY = "throttler-storage-fallback";

    /** 脚本主体：使用变量 {@code now}，{@code ARGV[1]} 为 period、{@code ARGV[2]} 为 interval（微秒）。 */
    public static final String SCRIPT_BODY = """
            local period = tonumber(ARGV[1])
            local interval = tonumber(ARGV[2])
            local tat = now
            local stored = redis.call('GET', KEYS[1])
            if stored then
              tat = math.floor(tonumber(stored) * 1000 + 0.5)
            end
            if tat < now then
              tat = now
            end
            local wait = (tat - now) + interval - period
            if wait > 0 then
              return {0, 0, tat - now, wait}
            end
            local new_tat = tat + interval
            redis.call('SET', KEYS[1], string.format('%.3f', new_tat / 1000), 'PX', math.ceil((new_tat - now) / 1000))
            return {1, math.floor((period - (new_tat - now)) / interval), new_tat - now, 0}
            """;

    @SuppressWarnings("rawtypes")
    static final RedisScript<List> SCRIPT = RedisScript.of(
            "local t = redis.call('TIME')\nlocal now = tonumber(t[1]) * 1000000 + tonumber(t[2])\n" + SCRIPT_BODY,
            List.class);

    private static final Logger LOG = LoggerFactory.getLogger(RateLimiter.class);

    private final @Nullable StringRedisTemplate redis;
    private final @Nullable AutoCloseable connection;
    private final AppMetrics metrics;
    private final Supplier<@Nullable AlertReporter> alerts;
    private final LongSupplier nowMicros;
    private final MemoryGcra memory = new MemoryGcra();
    private final AtomicBoolean degraded = new AtomicBoolean();

    public RateLimiter(
            @Nullable StringRedisTemplate redis,
            @Nullable AutoCloseable connection,
            AppMetrics metrics,
            Supplier<@Nullable AlertReporter> alerts,
            Clock clock) {
        this(redis, connection, metrics, alerts, () -> micros(clock.instant()));
    }

    RateLimiter(
            @Nullable StringRedisTemplate redis,
            @Nullable AutoCloseable connection,
            AppMetrics metrics,
            Supplier<@Nullable AlertReporter> alerts,
            LongSupplier nowMicros) {
        this.redis = redis;
        this.connection = connection;
        this.metrics = metrics;
        this.alerts = alerts;
        this.nowMicros = nowMicros;
    }

    /** {@code throttle:} + sha256hex({@code <Controller>-<handler>-default-<tracker>}) + {@code :default:gcra}。 */
    public static String key(String handler, String tracker) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((handler + "-" + THROTTLER_NAME + "-" + tracker).getBytes(StandardCharsets.UTF_8));
            return "throttle:" + HexFormat.of().formatHex(digest) + ":" + THROTTLER_NAME + ":gcra";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean degraded() {
        return degraded.get();
    }

    public Gcra.Decision consume(String key, long periodMicros, int limit) {
        long interval = Gcra.interval(periodMicros, limit);
        if (redis != null) {
            try {
                Gcra.Decision decision = consumeInRedis(redis, key, periodMicros, interval);
                markRecovered();
                return decision;
            } catch (RuntimeException e) {
                markDegraded(e);
            }
        }
        return memory.consume(key, periodMicros, interval, nowMicros.getAsLong());
    }

    private static Gcra.Decision consumeInRedis(
            StringRedisTemplate redis, String key, long periodMicros, long interval) {
        List<?> reply = redis.execute(SCRIPT, List.of(key), Long.toString(periodMicros), Long.toString(interval));
        if (reply == null || reply.size() != 4) {
            throw new IllegalStateException("unexpected throttler script reply: " + reply);
        }
        return new Gcra.Decision(
                number(reply.get(0)) == 1, number(reply.get(1)), number(reply.get(2)), number(reply.get(3)));
    }

    private static long number(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("unexpected throttler script value: " + value);
    }

    private void markDegraded(RuntimeException error) {
        metrics.throttlerStorageFallback();
        if (!degraded.compareAndSet(false, true)) {
            return;
        }
        String reason = error.getMessage() != null
                ? error.getMessage()
                : error.getClass().getSimpleName();
        LOG.error("Redis throttler storage unavailable, falling back to in-memory storage: {}", reason);
        AlertReporter reporter = alerts.get();
        if (reporter != null) {
            Thread.ofVirtual().name("throttler-alert").start(() -> raiseAlert(reporter, reason));
        }
    }

    private static void raiseAlert(AlertReporter reporter, String reason) {
        try {
            reporter.raise(
                    "throttler",
                    AlertLevel.CRITICAL,
                    "限流存储 Redis 不可用，已降级为单实例内存计数：" + reason,
                    Map.of("reason", reason),
                    FALLBACK_ALERT_KEY);
        } catch (RuntimeException e) {
            LOG.error("Failed to raise throttler fallback alert", e);
        }
    }

    private void markRecovered() {
        if (degraded.compareAndSet(true, false)) {
            LOG.info("Redis throttler storage recovered");
        }
    }

    private static long micros(Instant instant) {
        return instant.getEpochSecond() * Gcra.MICROS_PER_SECOND + instant.getNano() / 1_000;
    }

    @Override
    public void destroy() throws Exception {
        memory.clear();
        if (connection != null) {
            connection.close();
        }
    }
}
