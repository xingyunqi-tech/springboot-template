package tech.xingyunqi.template.common.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AppMetricsTest {

    static final Map<String, String> NESTJS_METRICS = nestjsMetrics();

    static Map<String, String> nestjsMetrics() {
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("http_requests_total", "counter");
        metrics.put("http_request_duration_seconds", "histogram");
        metrics.put("http_active_connections", "gauge");
        metrics.put("database_query_duration_seconds", "histogram");
        metrics.put("cache_hits_total", "counter");
        metrics.put("cache_misses_total", "counter");
        metrics.put("queue_jobs_total", "counter");
        metrics.put("admin_alerts_total", "counter");
        metrics.put("admin_alerts_deduplicated_total", "counter");
        metrics.put("throttler_storage_fallback_total", "counter");
        return metrics;
    }

    static Map<String, String> types(String text) {
        Map<String, String> types = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("(?m)^# TYPE (\\S+) (\\S+)$").matcher(text);
        while (matcher.find()) {
            types.put(matcher.group(1), matcher.group(2));
        }
        return types;
    }

    @Test
    @DisplayName("未观测的指标同样输出 HELP 与 TYPE，顺序、类型与 nestjs 相同")
    void emptyRegistryMatchesNestjs() {
        String text = new AppMetrics("NestJS API").render();
        assertThat(types(text)).containsExactlyEntriesOf(NESTJS_METRICS);
        assertThat(text)
                .contains("# HELP admin_alerts_deduplicated_total Total number of admin alerts suppressed by an open"
                        + " alert with the same dedup key\n# TYPE admin_alerts_deduplicated_total counter\n\n")
                .contains("# HELP http_active_connections Number of active HTTP connections\n"
                        + "# TYPE http_active_connections gauge\nhttp_active_connections{app=\"NestJS API\"} 0\n\n")
                .endsWith("# HELP throttler_storage_fallback_total Total number of rate limit checks served by the"
                        + " in-memory storage because Redis was unavailable\n"
                        + "# TYPE throttler_storage_fallback_total counter\n"
                        + "throttler_storage_fallback_total{app=\"NestJS API\"} 0\n");
    }

    @Test
    @DisplayName("counter 标签在前、app 在后；histogram 桶为 le、app、标签，桶与 nestjs 相同")
    void sampleLayoutMatchesPromClient() {
        AppMetrics metrics = new AppMetrics("app");
        metrics.httpRequest("GET", "/api/v1/users/:id", "401", 0.003);
        metrics.httpRequest("GET", "/api/v1/users/:id", "401", 0.2);
        String text = metrics.render();

        assertThat(text)
                .contains(
                        "http_requests_total{method=\"GET\",path=\"/api/v1/users/:id\",status=\"401\",app=\"app\"} 2\n")
                .contains("http_request_duration_seconds_bucket{le=\"0.005\",app=\"app\",method=\"GET\","
                        + "path=\"/api/v1/users/:id\",status=\"401\"} 1\n")
                .contains("http_request_duration_seconds_bucket{le=\"0.25\",app=\"app\",method=\"GET\","
                        + "path=\"/api/v1/users/:id\",status=\"401\"} 2\n")
                .contains("http_request_duration_seconds_sum{app=\"app\",method=\"GET\",path=\"/api/v1/users/:id\","
                        + "status=\"401\"} 0.203\n")
                .contains("http_request_duration_seconds_count{app=\"app\",method=\"GET\",path=\"/api/v1/users/:id\","
                        + "status=\"401\"} 2\n");
        Matcher buckets = Pattern.compile("http_request_duration_seconds_bucket\\{le=\"([^\"]+)\"")
                .matcher(text);
        StringBuilder les = new StringBuilder();
        while (buckets.find()) {
            les.append(buckets.group(1)).append(' ');
        }
        assertThat(les.toString().strip()).isEqualTo("0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10 +Inf");
    }

    @Test
    @DisplayName("数据库直方图桶与 nestjs 相同，值落在第一个不小于它的桶")
    void databaseBuckets() {
        AppMetrics metrics = new AppMetrics("app");
        metrics.databaseQuery("SELECT", "user", 0.001);
        String text = metrics.render();
        assertThat(text)
                .contains("database_query_duration_seconds_bucket{le=\"0.001\",app=\"app\",operation=\"SELECT\","
                        + "table=\"user\"} 1\n")
                .contains("le=\"1\",app=\"app\",operation=\"SELECT\",table=\"user\"} 1\n")
                .doesNotContain("le=\"2.5\",app=\"app\",operation");
        assertThat(metrics.value("database_query_duration_seconds", "SELECT", "user"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("记录函数累加对应指标，标签值按 prom-client 转义")
    void recordersAndEscaping() {
        AppMetrics metrics = new AppMetrics("a\"b");
        metrics.cacheHit("cache");
        metrics.cacheMiss("cache");
        metrics.queueJob("mail", "completed");
        metrics.adminAlert("probe", "WARN");
        metrics.adminAlertDeduplicated("probe");
        metrics.throttlerStorageFallback();
        metrics.connectionOpened();
        assertThat(metrics.value("cache_hits_total", "cache")).isEqualTo(1);
        assertThat(metrics.value("queue_jobs_total", "mail", "completed")).isEqualTo(1);
        assertThat(metrics.value("admin_alerts_total", "probe", "WARN")).isEqualTo(1);
        assertThat(metrics.value("http_active_connections")).isEqualTo(1);
        assertThat(metrics.render())
                .contains("cache_misses_total{cache_name=\"cache\",app=\"a\\\"b\"} 1\n")
                .contains("throttler_storage_fallback_total{app=\"a\\\"b\"} 1\n");
    }

    @ParameterizedTest
    @CsvSource({
        "0,0",
        "1,1",
        "2.5,2.5",
        "0.06600000000000003,0.06600000000000003",
        "0.0005,0.0005",
        "1e-7,1e-7",
        "1e21,1e+21",
        "123456789012,123456789012"
    })
    @DisplayName("数值按 JavaScript String(number) 输出")
    void jsNumbers(double value, String expected) {
        assertThat(PromText.number(value)).isEqualTo(expected);
    }
}
