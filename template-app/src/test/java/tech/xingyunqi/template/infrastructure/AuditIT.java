package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.contracttest.AuditProbeController;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuditProbeController.class})
class AuditIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("delete from audit_logs");
    }

    List<Map<String, Object>> logs() {
        return jdbc.queryForList(
                "select \"userId\", action, \"entityType\", \"entityId\", \"oldValue\"::text as old,"
                        + " \"newValue\"::text as new, \"ipAddress\", \"userAgent\", \"requestId\" from audit_logs order by id");
    }

    @Test
    @DisplayName("公开路由成功后记录校验后的请求体（脱敏、剔除未知字段），实体 id 取路由参数，用户为空")
    void recordsSuccessfulRequest() throws Exception {
        String token = new AuthApi(mvc).loginData(AuthApi.JOHN).path("token").asString();
        MockHttpServletResponse response = mvc.perform(patch("/api/v1/audit-probe/items/5")
                        .contentType("application/json")
                        .content("{\"firstName\":\"Ann\",\"oldPassword\":\"" + AuthApi.PASSWORD + "\",\"unknown\":1}")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Forwarded-For", "198.51.100.1, 10.0.0.1")
                        .header("User-Agent", "probe/1.0"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        List<Map<String, Object>> logs = logs();
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst())
                .containsEntry("action", "UPDATE")
                .containsEntry("entityType", "Item")
                .containsEntry("entityId", "5")
                .containsEntry("userId", null)
                .containsEntry("old", null)
                .containsEntry("new", "{\"firstName\": \"Ann\", \"oldPassword\": \"[REDACTED]\"}")
                .containsEntry("ipAddress", "198.51.100.1")
                .containsEntry("userAgent", "probe/1.0")
                .containsEntry("requestId", response.getHeader("X-Request-Id"));
    }

    @Test
    @DisplayName("鉴权路由记录当前用户 id")
    void recordsUserOfAuthenticatedRoute() throws Exception {
        String token = new AuthApi(mvc).loginData(AuthApi.JOHN).path("token").asString();
        int userId = AuthApi.payload(token).path("id").asInt();
        assertThat(mvc.perform(patch("/api/v1/audit-probe/me/items/5")
                                .contentType("application/json")
                                .content("{\"firstName\":\"Ann\"}")
                                .header("Authorization", "Bearer " + token))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(logs())
                .singleElement()
                .satisfies(log -> assertThat(log)
                        .containsEntry("entityId", "5")
                        .containsEntry("userId", Integer.toString(userId)));
    }

    @Test
    @DisplayName("includeResponse 记录脱敏后的响应，实体 id 取响应 id；失败的请求不记录")
    void recordsResponseAndSkipsFailures() throws Exception {
        MockHttpServletResponse created = mvc.perform(
                        post("/api/v1/audit-probe/items").with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        }))
                .andReturn()
                .getResponse();
        MockHttpServletResponse failed =
                mvc.perform(post("/api/v1/audit-probe/fail")).andReturn().getResponse();

        assertThat(created.getStatus()).isEqualTo(200);
        assertThat(failed.getStatus()).isEqualTo(422);
        assertThat(logs())
                .singleElement()
                .satisfies(log -> assertThat(log)
                        .containsEntry("action", "CREATE")
                        .containsEntry("entityId", "99")
                        .containsEntry("userId", null)
                        .containsEntry("ipAddress", "203.0.113.7")
                        .containsEntry("new", "{\"id\": 99, \"name\": \"n\", \"accessToken\": \"[REDACTED]\"}"));
    }

    @Test
    @DisplayName("审计写入失败不影响响应")
    void auditFailureDoesNotAffectResponse() throws Exception {
        assertThat(mvc.perform(post("/api/v1/audit-probe/broken"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(logs()).isEmpty();
    }
}
