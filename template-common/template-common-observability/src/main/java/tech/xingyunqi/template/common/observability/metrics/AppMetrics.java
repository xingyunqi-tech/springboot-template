package tech.xingyunqi.template.common.observability.metrics;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** 与 nestjs {@code metrics.module.ts} 同名、同类型、同标签、同桶、同 HELP 的十个自定义指标，按 prom-client 格式输出并带 {@code app} 标签。 */
public final class AppMetrics {

    public static final String UNMATCHED_ROUTE = "<unmatched>";

    static final double[] HTTP_BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};
    static final double[] DATABASE_BUCKETS = {0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1};

    private final String app;

    private final MetricFamily.Counter httpRequests = new MetricFamily.Counter(
            "http_requests_total", "Total number of HTTP requests", "method", "path", "status");
    private final MetricFamily.Histogram httpDuration = new MetricFamily.Histogram(
            "http_request_duration_seconds",
            "Duration of HTTP requests in seconds",
            HTTP_BUCKETS,
            "method",
            "path",
            "status");
    private final MetricFamily.Gauge activeConnections =
            new MetricFamily.Gauge("http_active_connections", "Number of active HTTP connections");
    private final MetricFamily.Histogram databaseDuration = new MetricFamily.Histogram(
            "database_query_duration_seconds",
            "Duration of database queries in seconds",
            DATABASE_BUCKETS,
            "operation",
            "table");
    private final MetricFamily.Counter cacheHits =
            new MetricFamily.Counter("cache_hits_total", "Total number of cache hits", "cache_name");
    private final MetricFamily.Counter cacheMisses =
            new MetricFamily.Counter("cache_misses_total", "Total number of cache misses", "cache_name");
    private final MetricFamily.Counter queueJobs =
            new MetricFamily.Counter("queue_jobs_total", "Total number of queue jobs", "queue_name", "status");
    private final MetricFamily.Counter adminAlerts =
            new MetricFamily.Counter("admin_alerts_total", "Total number of admin alerts raised", "type", "level");
    private final MetricFamily.Counter adminAlertsDeduplicated = new MetricFamily.Counter(
            "admin_alerts_deduplicated_total",
            "Total number of admin alerts suppressed by an open alert with the same dedup key",
            "type");
    private final MetricFamily.Counter throttlerStorageFallback = new MetricFamily.Counter(
            "throttler_storage_fallback_total",
            "Total number of rate limit checks served by the in-memory storage because Redis was unavailable");

    private final List<MetricFamily> families = List.of(
            httpRequests,
            httpDuration,
            activeConnections,
            databaseDuration,
            cacheHits,
            cacheMisses,
            queueJobs,
            adminAlerts,
            adminAlertsDeduplicated,
            throttlerStorageFallback);

    public AppMetrics(String app) {
        this.app = app;
    }

    public void httpRequest(String method, String path, String status, double seconds) {
        httpRequests.inc(method, path, status);
        httpDuration.observe(seconds, method, path, status);
    }

    public void connectionOpened() {
        activeConnections.inc();
    }

    public void connectionClosed() {
        activeConnections.dec();
    }

    public void databaseQuery(String operation, String table, double seconds) {
        databaseDuration.observe(seconds, operation, table);
    }

    public void cacheHit(String cacheName) {
        cacheHits.inc(cacheName);
    }

    public void cacheMiss(String cacheName) {
        cacheMisses.inc(cacheName);
    }

    public void queueJob(String queueName, String status) {
        queueJobs.inc(queueName, status);
    }

    public void adminAlert(String type, String level) {
        adminAlerts.inc(type, level);
    }

    public void adminAlertDeduplicated(String type) {
        adminAlertsDeduplicated.inc(type);
    }

    public void throttlerStorageFallback() {
        throttlerStorageFallback.inc();
    }

    /** 当前计数（counter）或观测次数（histogram 的 {@code _count}），供测试与诊断使用；未出现过的标签组合为 0。 */
    public double value(String name, String... labels) {
        for (MetricFamily family : families) {
            if (!family.name.equals(name)) {
                continue;
            }
            return switch (family) {
                case MetricFamily.Counter counter -> counter.value(labels);
                case MetricFamily.Gauge gauge -> gauge.value();
                case MetricFamily.Histogram histogram -> {
                    MetricFamily.Histogram.@Nullable Series series = histogram.series(labels);
                    yield series == null ? 0 : series.count();
                }
            };
        }
        throw new IllegalArgumentException("unknown metric " + name);
    }

    /** 各指标族之间空一行，末尾换行（prom-client {@code registry.metrics()}）。 */
    public String render() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < families.size(); i++) {
            if (i > 0) {
                out.append("\n\n");
            }
            families.get(i).write(out, app);
        }
        return out.append('\n').toString();
    }
}
