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
class FavoritesIT {

    static final String URL = "/api/v1/favorites";
    static final String UUID = "00000000-0000-4000-8000-00000000000A";

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
        user = TestUsers.register(api, "fav");
    }

    AuthApi.Response call(String method, String path, Object body) throws Exception {
        return api.call(method, path, body, user.token());
    }

    @Test
    @DisplayName("未登录 401")
    void requiresAuthentication() throws Exception {
        assertThat(api.call("POST", URL + "/toggle", Map.of(), null).status()).isEqualTo(401);
        assertThat(api.call("DELETE", URL + "/1", null, null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("创建、重复 409、检查、切换、列表与删除")
    void lifecycle() throws Exception {
        AuthApi.Response created = call(
                "POST",
                URL,
                Map.of("targetType", "product", "targetId", "p1", "title", " <b>Phone</b> ", "extra", "{}"));
        assertThat(created.status()).isEqualTo(200);
        assertThat(new ArrayList<>(created.data().propertyNames()))
                .containsExactly(
                        "id", "userId", "targetType", "targetId", "title", "image", "extra", "subjectId", "createdAt");
        assertThat(created.data().path("title").asString()).isEqualTo("Phone");
        assertThat(created.data().path("image").isNull()).isTrue();
        int id = created.data().path("id").asInt();

        AuthApi.Response duplicate = call("POST", URL, Map.of("targetType", "product", "targetId", "p1"));
        assertThat(duplicate.status()).isEqualTo(409);

        AuthApi.Response checked = call("POST", URL + "/check", Map.of("targetType", "product", "targetId", "p1"));
        assertThat(checked.data().toString()).isEqualTo("{\"isFavorited\":true,\"favoriteId\":" + id + "}");
        AuthApi.Response unchecked = call("POST", URL + "/check", Map.of("targetType", "store", "targetId", "s1"));
        assertThat(unchecked.data().toString()).isEqualTo("{\"isFavorited\":false}");

        Map<String, Object> store = Map.of("targetType", "store", "targetId", "s1");
        AuthApi.Response on = call("POST", URL + "/toggle", store);
        assertThat(on.data().path("isFavorited").asBoolean()).isTrue();
        assertThat(on.data().path("favorite").path("targetId").asString()).isEqualTo("s1");

        AuthApi.Response list = call("GET", URL, null);
        assertThat(list.data().path("total").asInt()).isEqualTo(2);
        assertThat(list.data().path("list").get(0).path("targetOffline").isNull())
                .isTrue();
        assertThat(call("GET", URL + "?targetType=store&limit=1", null)
                        .data()
                        .path("total")
                        .asInt())
                .isEqualTo(1);
        assertThat(call("GET", URL + "?limit=100", null).data().path("limit").asInt())
                .isEqualTo(50);
        assertThat(call("GET", URL + "?targetType=bad&page=0", null).status()).isEqualTo(422);

        AuthApi.Response off = call("POST", URL + "/toggle", store);
        assertThat(off.data().toString()).isEqualTo("{\"isFavorited\":false}");

        assertThat(call("DELETE", URL + "/" + id, null).status()).isEqualTo(200);
        assertThat(call("DELETE", URL + "/" + id, null).status()).isEqualTo(404);
        assertThat(call("DELETE", URL + "/abc", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("题目 403 questionNotAccessible，试卷 404 paperNotFound；列表中题目、试卷标记为下架")
    void examTargets() throws Exception {
        AuthApi.Response question = call("POST", URL, Map.of("targetType", "question", "targetId", UUID));
        assertThat(question.status()).isEqualTo(403);
        assertThat(question.data().path("errorKey").asString()).isEqualTo("questionNotAccessible");
        AuthApi.Response paper = call("POST", URL + "/toggle", Map.of("targetType", "paper", "targetId", "p1"));
        assertThat(paper.status()).isEqualTo(404);

        jdbc.update(
                "insert into favorite (\"userId\", \"targetType\", \"targetId\", \"subjectId\") values (?, 'paper', ?, ?::uuid)",
                user.id(),
                UUID.toLowerCase(),
                UUID);
        AuthApi.Response checked = call("POST", URL + "/check", Map.of("targetType", "paper", "targetId", UUID));
        assertThat(checked.data().path("isFavorited").asBoolean()).isTrue();
        AuthApi.Response list = call("GET", URL + "?subjectId=" + UUID, null);
        assertThat(list.data().path("total").asInt()).isEqualTo(1);
        assertThat(list.data().path("list").get(0).path("targetOffline").asBoolean())
                .isTrue();
        assertThat(list.data().path("list").get(0).path("subjectId").asString()).isEqualTo(UUID.toLowerCase());
    }

    @Test
    @DisplayName("非本人的收藏不可删除；账号清理删除全部收藏")
    void ownershipAndCleaner() throws Exception {
        int id = call("POST", URL, Map.of("targetType", "article", "targetId", "a1"))
                .data()
                .path("id")
                .asInt();
        TestUsers.TestUser other = TestUsers.register(api, "fav-other");
        assertThat(api.call("DELETE", URL + "/" + id, null, other.token()).status())
                .isEqualTo(404);

        AccountDataCleaner cleaner = cleaners.stream()
                .filter(c -> c.name().equals("favorites"))
                .findFirst()
                .orElseThrow();
        assertThat(cleaner.order()).isEqualTo(70);
        cleaner.clean(user.id());
        assertThat(jdbc.queryForObject("select count(*) from favorite where \"userId\" = ?", Integer.class, user.id()))
                .isZero();
    }
}
