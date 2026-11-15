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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {
            "HEALTH_MEMORY_HEAP_THRESHOLD_MB=1",
            "DATABASE_CONNECTION_TIMEOUT_MS=250",
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none"
        })
@AutoConfigureMockMvc
class HealthFailureIT {

    @Autowired
    MockMvc mvc;

    void assertUnavailable(String path) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(JsonMapper.builder().build().readTree(response.getContentAsString()))
                .isEqualTo(HealthIT.UNAVAILABLE);
        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    @Test
    @DisplayName("已用堆超过 HEALTH_MEMORY_HEAP_THRESHOLD_MB 时存活探针 503")
    void heapAboveThreshold() throws Exception {
        assertUnavailable("/api/v1/health/live");
    }

    @Test
    @DisplayName("数据库不可用时就绪探针与完整检查 503")
    void databaseDown() throws Exception {
        assertUnavailable("/api/v1/health/ready");
        assertUnavailable("/api/v1/health");
    }
}
