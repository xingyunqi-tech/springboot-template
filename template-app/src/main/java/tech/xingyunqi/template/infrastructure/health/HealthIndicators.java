package tech.xingyunqi.template.infrastructure.health;

import java.io.File;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.redis.RedisAutoConfiguration;
import tech.xingyunqi.template.integrations.alipay.AlipayProperties;
import tech.xingyunqi.template.integrations.wechat.WechatProperties;
import tools.jackson.databind.json.JsonMapper;

/** @nestjs/terminus 健康指示器：每项为 {@code {<key>: {...附加字段, status: "up"|"down"}}}，并发执行、按声明顺序汇总。 */
@Component
public class HealthIndicators implements DisposableBean {

    static final double DISK_THRESHOLD = 0.9;
    static final int DATABASE_TIMEOUT_MS = 1000;
    static final int POOL_UTILIZATION_THRESHOLD = 90;
    static final int REDIS_TIMEOUT_SECONDS = 5;
    static final String WECHAT_API_URL = "https://api.weixin.qq.com/cgi-bin/getcallbackip";
    static final String ALIPAY_GATEWAY_URL = "https://openapi.alipay.com/gateway.do";
    static final String POOL_STATS_SQL = """
            SELECT
              numbackends as active_connections,
              (SELECT setting::int FROM pg_settings WHERE name = 'max_connections') as max_connections
            FROM pg_stat_database
            WHERE datname = current_database()
            """;

    private static final Logger LOG = LoggerFactory.getLogger(HealthIndicators.class);

    /** 一项检查的结果。 */
    public record Check(String key, Map<String, Object> data) {

        boolean up() {
            return "up".equals(data.get("status"));
        }
    }

    /**
     * terminus {@code HealthCheckService.check} 的汇总：{@code info} 为 up 的项，{@code error} 为 down 的项，{@code details} 为两者合并。
     */
    public record Result(Map<String, Object> info, Map<String, Object> error, Map<String, Object> details) {

        public boolean ok() {
            return error.isEmpty();
        }
    }

    private final DataSource dataSource;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final ExternalApiProbe probe;
    private final HealthProperties properties;
    private final WechatProperties wechat;
    private final AlipayProperties alipay;
    private final boolean redisEnabled;
    private final JsonMapper json;
    private final LongSupplier heapUsed;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public HealthIndicators(
            DataSource dataSource,
            ObjectProvider<StringRedisTemplate> redis,
            ExternalApiProbe probe,
            HealthProperties properties,
            WechatProperties wechat,
            AlipayProperties alipay,
            Environment environment,
            JsonMapper json) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.probe = probe;
        this.properties = properties;
        this.wechat = wechat;
        this.alipay = alipay;
        this.redisEnabled = RedisAutoConfiguration.redisEnabled(environment);
        this.json = json;
        this.heapUsed = HeapUsage::afterGc;
    }

    public Result full() {
        List<Supplier<Check>> checks = new ArrayList<>(List.of(
                () -> memoryHeap("memory_heap"),
                () -> disk("disk", new File("/")),
                () -> database("database"),
                () -> databasePool("database_pool")));
        if (redisEnabled) {
            checks.add(() -> redis("redis"));
        }
        checks.add(() -> wechatApi("wechat_api"));
        checks.add(() -> alipayApi("alipay_api"));
        return run(checks);
    }

    public Result liveness() {
        return run(List.of(() -> memoryHeap("memory_heap")));
    }

    public Result readiness() {
        return run(List.of(() -> database("database")));
    }

    Result run(List<Supplier<Check>> checks) {
        List<CompletableFuture<Check>> futures = checks.stream()
                .map(check -> CompletableFuture.supplyAsync(check, executor))
                .toList();
        Map<String, Object> info = new LinkedHashMap<>();
        Map<String, Object> error = new LinkedHashMap<>();
        for (CompletableFuture<Check> future : futures) {
            Check check = future.join();
            (check.up() ? info : error).put(check.key(), check.data());
        }
        Map<String, Object> details = new LinkedHashMap<>(info);
        details.putAll(error);
        if (!error.isEmpty()) {
            LOG.error("Health Check has failed! {}", json.writeValueAsString(error));
        }
        return new Result(info, error, details);
    }

    static Check up(String key, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put("status", "up");
        return new Check(key, result);
    }

    static Check down(String key, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put("status", "down");
        return new Check(key, result);
    }

    static Map<String, Object> storageExceeded(String keyword) {
        return Map.of("message", "Used " + keyword + " exceeded the set threshold");
    }

    Check memoryHeap(String key) {
        return memoryHeap(key, heapUsed.getAsLong(), properties.memoryHeapThresholdBytes());
    }

    static Check memoryHeap(String key, long used, long threshold) {
        return used > threshold ? down(key, storageExceeded("heap")) : up(key, Map.of());
    }

    static Check disk(String key, File path) {
        long total = path.getTotalSpace();
        if (total == 0) {
            return down(key, storageExceeded("disk storage"));
        }
        double used = (double) (total - path.getUsableSpace()) / total;
        return used > DISK_THRESHOLD ? down(key, storageExceeded("disk storage")) : up(key, Map.of());
    }

    Check database(String key) {
        long started = System.nanoTime();
        Future<?> ping = executor.submit(() -> {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
            return null;
        });
        try {
            ping.get(DATABASE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return up(key, Map.of("responseTime", elapsedMs(started)));
        } catch (TimeoutException e) {
            ping.cancel(true);
            return down(
                    key,
                    ordered(
                            "message",
                            "timeout of " + DATABASE_TIMEOUT_MS + "ms exceeded",
                            "responseTime",
                            elapsedMs(started)));
        } catch (ExecutionException e) {
            return down(key, ordered("message", message(e.getCause()), "responseTime", elapsedMs(started)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down(key, ordered("message", message(e), "responseTime", elapsedMs(started)));
        }
    }

    Check databasePool(String key) {
        Map<String, Object> stats = new LinkedHashMap<>();
        long utilization;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(POOL_STATS_SQL)) {
            if (!row.next()) {
                throw new IllegalStateException("Unable to retrieve database pool statistics");
            }
            int active = row.getInt("active_connections");
            int max = row.getInt("max_connections");
            stats.put("activeConnections", active);
            stats.put("maxConnections", max);
            utilization = jsRound(active * 100.0 / max);
            stats.put("utilizationPercent", utilization);
        } catch (Exception e) {
            return down(key, Map.of("message", message(e)));
        }
        return utilization > POOL_UTILIZATION_THRESHOLD ? down(key, stats) : up(key, stats);
    }

    Check redis(String key) {
        Future<String> ping = executor.submit(() -> {
            String pong = redis.getObject().execute(connection -> connection.ping(), true);
            return pong == null ? "" : pong;
        });
        try {
            return "PONG".equals(ping.get(REDIS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    ? up(key, Map.of())
                    : down(key, Map.of());
        } catch (TimeoutException e) {
            ping.cancel(true);
            return down(key, Map.of("message", "Command timed out"));
        } catch (ExecutionException e) {
            return down(key, Map.of("message", message(e.getCause())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down(key, Map.of("message", message(e)));
        }
    }

    Check wechatApi(String key) {
        if (!wechat.configured()) {
            return up(key, Map.of("message", "WeChat not configured, skipped"));
        }
        return external(key, "GET", WECHAT_API_URL, false);
    }

    Check alipayApi(String key) {
        if (!alipay.configured()) {
            return up(key, Map.of("message", "Alipay not configured, skipped"));
        }
        return external(key, "HEAD", ALIPAY_GATEWAY_URL, true);
    }

    private Check external(String key, String method, String url, boolean methodNotAllowedIsUp) {
        int status;
        try {
            status = probe.status(method, URI.create(url));
        } catch (HttpTimeoutException e) {
            return down(key, Map.of("message", "This operation was aborted"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down(key, Map.of("message", "This operation was aborted"));
        } catch (Exception e) {
            return down(key, Map.of("message", "fetch failed"));
        }
        if ((status >= 200 && status < 300) || (methodNotAllowedIsUp && status == 405)) {
            return up(key, Map.of("responseTime", "OK"));
        }
        return down(key, Map.of("statusCode", status));
    }

    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static long elapsedMs(long startedNanos) {
        return jsRound((System.nanoTime() - startedNanos) / 1e6);
    }

    static long jsRound(double value) {
        return (long) Math.floor(value + 0.5);
    }

    private static String message(@Nullable Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
