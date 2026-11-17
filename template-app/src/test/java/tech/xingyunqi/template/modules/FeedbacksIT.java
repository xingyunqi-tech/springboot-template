package tech.xingyunqi.template.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.infrastructure.accountcleanup.AccountDataCleaner;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FeedbacksIT {

    static final String URL = "/api/v1/feedbacks";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    List<AccountDataCleaner> cleaners;

    AuthApi api;
    TestUsers.TestUser user;

    @BeforeEach
    void setUp() throws Exception {
        api = new AuthApi(mvc);
        user = TestUsers.register(api, "fb");
    }

    AuthApi.Response call(String method, String path, Object body) throws Exception {
        return api.call(method, path, body, user.token());
    }

    int create(Map<String, Object> body) throws Exception {
        AuthApi.Response created = call("POST", URL, body);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
        return created.data().path("id").asInt();
    }

    @Test
    @DisplayName("未登录 401，校验失败 422")
    void errors() throws Exception {
        assertThat(api.call("GET", URL, null, null).status()).isEqualTo(401);
        assertThat(call("POST", URL, Map.of("type", "question_error", "content", ""))
                        .status())
                .isEqualTo(422);
        assertThat(call(
                                "POST",
                                URL,
                                Map.of("type", "bug", "content", "c", "images", List.of("a", "b", "c", "d", "e", "f")))
                        .status())
                .isEqualTo(422);
        assertThat(call("GET", URL + "?type=ai_content&page=0", null).status()).isEqualTo(422);
        assertThat(call("GET", URL + "?page=1e400", null).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("创建：images 未提供时不输出该键，状态为 pending")
    void createFeedback() throws Exception {
        AuthApi.Response plain = call("POST", URL, Map.of("type", "bug", "content", "c"));
        assertThat(new ArrayList<>(plain.data().propertyNames()))
                .containsExactly(
                        "id",
                        "userId",
                        "type",
                        "content",
                        "contact",
                        "status",
                        "reply",
                        "questionId",
                        "questionVersion",
                        "reason",
                        "errataId",
                        "messageId",
                        "messageSnapshot",
                        "createdAt",
                        "updatedAt");
        assertThat(plain.data().path("status").asString()).isEqualTo("pending");

        AuthApi.Response full = call(
                "POST",
                URL,
                Map.of("type", "suggestion", "content", "<b>s</b>", "images", List.of("a", "b"), "contact", "x"));
        assertThat(full.data().path("images").toString()).isEqualTo("[\"a\",\"b\"]");
        assertThat(full.data().path("content").asString()).isEqualTo("<b>s</b>");
        assertThat(full.data().path("contact").asString()).isEqualTo("x");
    }

    @Test
    @DisplayName("列表：默认只含通用类型，未满页时 total 为 skip + 本页行数，满页时计数")
    void listFeedbacks() throws Exception {
        create(Map.of("type", "bug", "content", "1"));
        create(Map.of("type", "other", "content", "2"));
        create(Map.of("type", "complaint", "content", "3"));
        jdbc.update(
                "insert into feedback (\"userId\", type, content, \"questionVersion\", reason) values (?, 'question_error', 'r', 3, 'other')",
                user.id());
        jdbc.update("insert into feedback (\"userId\", type, content) values (?, 'ai_content', 'r')", user.id());

        AuthApi.Response all = call("GET", URL, null);
        assertThat(all.data().path("total").asInt()).isEqualTo(3);
        AuthApi.Response reports = call("GET", URL + "?type=question_error", null);
        assertThat(reports.data().path("total").asInt()).isEqualTo(1);
        assertThat(reports.data().path("list").get(0).path("questionVersion").asInt())
                .isEqualTo(3);

        AuthApi.Response fractional = call("GET", URL + "?page=2&limit=1.5", null);
        assertThat(fractional.data().path("total").toString()).isEqualTo("2.5");
        assertThat(fractional.data().path("limit").toString()).isEqualTo("1.5");
        assertThat(fractional.data().path("totalPages").asInt()).isEqualTo(2);

        AuthApi.Response full = call("GET", URL + "?limit=1", null);
        assertThat(full.data().path("total").asInt()).isEqualTo(3);
        AuthApi.Response beyond = call("GET", URL + "?page=5&limit=1", null);
        assertThat(beyond.data().path("total").asInt()).isEqualTo(3);
        AuthApi.Response decimalPage = call("GET", URL + "?page=1.3&limit=1", null);
        assertThat(decimalPage.data().path("page").toString()).isEqualTo("1.3");
        assertThat(call("GET", URL + "?status=closed", null)
                        .data()
                        .path("total")
                        .asInt())
                .isZero();
        assertThat(call("GET", URL + "?limit=100", null).data().path("limit").asInt())
                .isEqualTo(50);
    }

    @Test
    @DisplayName("详情：仅本人且为可查询类型，否则 404；账号清理解除归属")
    void detailAndCleaner() throws Exception {
        int id = create(Map.of("type", "bug", "content", "mine", "contact", "c", "images", List.of("i")));
        assertThat(call("GET", URL + "/" + id, null).data().path("content").asString())
                .isEqualTo("mine");
        Integer report = jdbc.queryForObject(
                "insert into feedback (\"userId\", type, content) values (?, 'ai_content', 'r') returning id",
                Integer.class,
                user.id());
        assertThat(call("GET", URL + "/" + report, null).status()).isEqualTo(404);
        assertThat(call("GET", URL + "/999999", null).status()).isEqualTo(404);
        assertThat(call("GET", URL + "/abc", null).status()).isEqualTo(400);
        TestUsers.TestUser other = TestUsers.register(api, "fb-other");
        assertThat(api.call("GET", URL + "/" + id, null, other.token()).status())
                .isEqualTo(404);

        AccountDataCleaner cleaner = cleaners.stream()
                .filter(c -> c.name().equals("feedbacks"))
                .findFirst()
                .orElseThrow();
        assertThat(cleaner.order()).isEqualTo(90);
        cleaner.clean(user.id());
        Map<String, Object> row = jdbc.queryForMap("select \"userId\", contact, images from feedback where id = ?", id);
        assertThat(row.get("userId")).isNull();
        assertThat(row.get("contact")).isNull();
        assertThat(row.get("images")).isNull();
    }
}
