package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tech.xingyunqi.template.infrastructure.health.ExternalApiProbe;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {
            "HEALTH_MEMORY_HEAP_THRESHOLD_MB=100000",
            "METRICS_TOKEN=" + HealthIT.TOKEN,
            "WECHAT_APP_ID=wx",
            "WECHAT_APP_SECRET=secret",
            "ALIPAY_APP_ID=ali"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthIT {

    static final String TOKEN = "monitoring-token-0123456789";
    static final String NO_CACHE = "no-cache, no-store, must-revalidate";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNode UNAVAILABLE =
            JSON.readTree("{\"code\":503,\"message\":\"SERVICE_UNAVAILABLE\",\"data\":null}");
    static final JsonNode FORBIDDEN = JSON.readTree("{\"code\":403,\"message\":\"Forbidden resource\",\"data\":null}");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ExternalApiProbe probe;

    @BeforeEach
    void externalApisAreReachable() throws Exception {
        when(probe.status(eq("GET"), any())).thenReturn(200);
        when(probe.status(eq("HEAD"), any())).thenReturn(405);
    }

    MockHttpServletResponse send(String path, @Nullable String authorization) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }

    @Test
    @DisplayName("存活探针只有 memory_heap，200 并带 no-cache 头")
    void liveness() throws Exception {
        MockHttpServletResponse response = send("/api/v1/health/live", null);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo(NO_CACHE);
        assertThat(body(response))
                .isEqualTo(JSON.readTree("{\"code\":200,\"message\":\"success\",\"data\":{\"status\":\"ok\","
                        + "\"info\":{\"memory_heap\":{\"status\":\"up\"}},\"error\":{},"
                        + "\"details\":{\"memory_heap\":{\"status\":\"up\"}}}}"));
    }

    @Test
    @DisplayName("就绪探针只有 database，附带整数 responseTime 且 status 在后")
    void readiness() throws Exception {
        MockHttpServletResponse response = send("/api/v1/health/ready", null);
        JsonNode data = body(response).path("data");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo(NO_CACHE);
        assertThat(data.path("status").asString()).isEqualTo("ok");
        assertThat(data.path("info").path("database").propertyNames()).containsExactly("responseTime", "status");
        assertThat(data.path("info").path("database").path("responseTime").isIntegralNumber())
                .isTrue();
        assertThat(data.path("details")).isEqualTo(data.path("info"));
    }

    @Test
    @DisplayName("完整检查的检查项顺序与 nestjs 相同，支付接口按方法探测")
    void fullCheck() throws Exception {
        MockHttpServletResponse response = send("/api/v1/health", "Bearer " + TOKEN);
        JsonNode details = body(response).path("data").path("details");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo(NO_CACHE);
        assertThat(details.propertyNames())
                .containsExactly("memory_heap", "disk", "database", "database_pool", "wechat_api", "alipay_api");
        assertThat(details.path("database_pool").propertyNames())
                .containsExactly("activeConnections", "maxConnections", "utilizationPercent", "status");
        assertThat(details.path("wechat_api")).isEqualTo(JSON.readTree("{\"responseTime\":\"OK\",\"status\":\"up\"}"));
        assertThat(details.path("alipay_api")).isEqualTo(JSON.readTree("{\"responseTime\":\"OK\",\"status\":\"up\"}"));
        verify(probe).status("GET", URI.create("https://api.weixin.qq.com/cgi-bin/getcallbackip"));
        verify(probe).status("HEAD", URI.create("https://openapi.alipay.com/gateway.do"));
    }

    @Test
    @DisplayName("任一项 down 时 503，body 只有 SERVICE_UNAVAILABLE 信封且不带 Cache-Control")
    void downReturns503() throws Exception {
        when(probe.status(eq("HEAD"), any())).thenReturn(500);
        MockHttpServletResponse response = send("/api/v1/health", "Bearer " + TOKEN);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(body(response)).isEqualTo(UNAVAILABLE);
        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    @Test
    @DisplayName("完整检查要求监控令牌，scheme 不区分大小写；两个探针不要求")
    void monitoringToken() throws Exception {
        for (String header : new String[] {null, "Bearer " + TOKEN + "x", "Token " + TOKEN, "Bearer"}) {
            MockHttpServletResponse response = send("/api/v1/health", header);
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(body(response)).isEqualTo(FORBIDDEN);
        }
        assertThat(send("/api/v1/health", "bearer " + TOKEN).getStatus()).isEqualTo(200);
        for (String probePath : List.of("/api/v1/health/live", "/api/v1/health/ready")) {
            assertThat(send(probePath, null).getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("GET /api/health 不带 Cache-Control")
    void simpleHealthCheckHasNoCacheHeader() throws Exception {
        MockHttpServletResponse response = send("/api/health", null);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isNull();
    }
}
