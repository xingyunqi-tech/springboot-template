package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.sentry.Sentry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.support.Prometheus;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"APP_NAME=Metrics App", "HEALTH_MEMORY_HEAP_THRESHOLD_MB=100000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MetricsIT {

    static final Map<String, String> NESTJS_METRICS = nestjsMetrics();
    static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

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

    String scrape() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/metrics")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return response.getContentAsString();
    }

    int status(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("名称、类型与 nestjs 相同，所有样本带 app 标签，默认 JVM 指标在前")
    void namesTypesAndLabels() throws Exception {
        status("/");
        MockHttpServletResponse response =
                mvc.perform(get("/metrics")).andReturn().getResponse();
        String text = response.getContentAsString();

        assertThat(response.getHeader("Content-Type")).isEqualTo(CONTENT_TYPE);
        Map<String, String> declared = Prometheus.types(text);
        assertThat(declared).containsAllEntriesOf(NESTJS_METRICS);
        assertThat(declared).containsKey("jvm_memory_used_bytes");
        assertThat(text.indexOf("jvm_memory_used_bytes")).isLessThan(text.indexOf("# HELP http_requests_total"));
        assertThat(text).contains("# HELP http_requests_total Total number of HTTP requests\n");
        for (String line : text.split("\n")) {
            if (!line.isEmpty() && !line.startsWith("#")) {
                assertThat(line).contains("app=\"Metrics App\"");
            }
        }
        Matcher buckets = Pattern.compile(
                        "(?m)^http_request_duration_seconds_bucket\\{le=\"([^\"]+)\",app=\"Metrics App\",method=\"GET\","
                                + "path=\"/\",status=\"200\"}")
                .matcher(text);
        StringBuilder les = new StringBuilder();
        while (buckets.find()) {
            les.append(buckets.group(1)).append(' ');
        }
        assertThat(les.toString().strip()).isEqualTo("0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10 +Inf");
        assertThat(text).doesNotContain("_created");
    }

    @Test
    @DisplayName("不包信封、不带 API 前缀")
    void rawAndWithoutPrefix() throws Exception {
        assertThat(scrape()).doesNotStartWith("{");
        assertThat(status("/api/metrics")).isEqualTo(404);
        assertThat(status("/api/v1/metrics")).isEqualTo(404);
    }

    @Test
    @DisplayName("path 标签为 Express 写法的路由模板，未匹配为 <unmatched>，预检不计数")
    void routeLabels() throws Exception {
        assertThat(status("/api/v1/users/12")).isEqualTo(401);
        assertThat(status("/api/v1/nope")).isEqualTo(404);
        assertThat(status("/api/%68ealth")).isEqualTo(404);
        assertThat(status("/api/v1/health/live")).isEqualTo(200);
        mvc.perform(options("/api/v1/metrics-preflight").header("Origin", "http://a.example"));
        String text = scrape();

        assertThat(Prometheus.sample(text, "http_requests_total", "path", "/api/v1/users/:id", "status", "401"))
                .isNotNull();
        assertThat(Prometheus.sample(
                        text, "http_requests_total", "method", "GET", "path", "<unmatched>", "status", "404"))
                .isNotNull();
        assertThat(Prometheus.sample(text, "http_requests_total", "method", "GET", "path", "/api/v1/health/live"))
                .isNotNull();
        assertThat(Prometheus.sample(text, "http_requests_total", "path", "/api/health", "status", "404"))
                .isNull();
        assertThat(Prometheus.sample(text, "http_requests_total", "method", "OPTIONS"))
                .isNull();
        assertThat(Prometheus.sample(text, "http_active_connections")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("记录 MyBatis 查询耗时与缓存命中 / 未命中")
    void databaseAndCacheMetrics() throws Exception {
        AuthApi api = new AuthApi(mvc);
        String token = api.loginData(AuthApi.JOHN).path("token").asString();
        assertThat(api.call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(200);
        assertThat(api.call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(200);
        String text = scrape();

        assertThat(Prometheus.sample(
                        text, "database_query_duration_seconds_count", "operation", "SELECT", "table", "user"))
                .isPositive();
        assertThat(Prometheus.sample(
                        text, "database_query_duration_seconds_count", "operation", "INSERT", "table", "session"))
                .isPositive();
        assertThat(Prometheus.sample(text, "cache_misses_total", "cache_name", "cache"))
                .isPositive();
        assertThat(Prometheus.sample(text, "cache_hits_total", "cache_name", "cache"))
                .isPositive();
    }

    @Test
    @DisplayName("未配置微信 / 支付宝时跳过探测，未启用 Redis 时不检查 redis")
    void fullCheckWithoutIntegrations() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/health")).andReturn().getResponse();
        JsonNode details = JsonMapper.builder()
                .build()
                .readTree(response.getContentAsString())
                .path("data")
                .path("details");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(details.propertyNames())
                .containsExactly("memory_heap", "disk", "database", "database_pool", "wechat_api", "alipay_api");
        assertThat(details.path("wechat_api").toString())
                .isEqualTo("{\"message\":\"WeChat not configured, skipped\",\"status\":\"up\"}");
        assertThat(details.path("alipay_api").toString())
                .isEqualTo("{\"message\":\"Alipay not configured, skipped\",\"status\":\"up\"}");
    }

    @Test
    @DisplayName("OTEL_ENABLED 与 SENTRY_DSN 未设置时链路导出与 Sentry 都不启用")
    void telemetryDisabledByDefault() {
        assertThat(context.getBeanProvider(SpanExporter.class).stream()).isEmpty();
        assertThat(Sentry.isEnabled()).isFalse();
    }
}
