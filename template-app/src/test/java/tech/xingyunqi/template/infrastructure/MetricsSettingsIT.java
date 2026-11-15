package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.support.Prometheus;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"METRICS_PATH=/prom/", "METRICS_ENABLED=false", "METRICS_TOKEN=" + HealthIT.TOKEN})
@AutoConfigureMockMvc
class MetricsSettingsIT {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("METRICS_PATH 决定端点路径，配置令牌后要求 Bearer 令牌")
    void customPathWithToken() throws Exception {
        MockHttpServletResponse missing = mvc.perform(get("/prom")).andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(403);
        assertThat(JsonMapper.builder().build().readTree(missing.getContentAsString()))
                .isEqualTo(HealthIT.FORBIDDEN);
        assertThat(mvc.perform(get("/prom").header("Authorization", "Bearer " + HealthIT.TOKEN))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get("/metrics").header("Authorization", "Bearer " + HealthIT.TOKEN))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("METRICS_ENABLED=false 保留端点，不采集 HTTP 指标、不输出 JVM 默认指标")
    void disabledKeepsEndpoint() throws Exception {
        mvc.perform(get("/"));
        String text = mvc.perform(get("/prom").header("Authorization", "Bearer " + HealthIT.TOKEN))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(Prometheus.types(text))
                .containsKey("http_requests_total")
                .doesNotContainKey("jvm_memory_used_bytes");
        assertThat(Prometheus.sample(text, "http_requests_total")).isNull();
    }
}
