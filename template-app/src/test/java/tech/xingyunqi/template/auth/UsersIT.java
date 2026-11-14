package tech.xingyunqi.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.xingyunqi.template.auth.AuthApi.ADMIN;
import static tech.xingyunqi.template.auth.AuthApi.JOHN;
import static tech.xingyunqi.template.auth.AuthApi.PASSWORD;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UsersIT {

    @Autowired
    MockMvc mvc;

    AuthApi api;
    String admin;

    @BeforeEach
    void setUp() throws Exception {
        api = new AuthApi(mvc);
        admin = api.loginData(ADMIN).path("token").asString();
    }

    @Test
    @DisplayName("缺少权限 403，message 为 nestjs 原文")
    void missingPermission() throws Exception {
        String john = api.loginData(JOHN).path("token").asString();
        AuthApi.Response response = api.call("GET", "/api/v1/users", null, john);
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().toString())
                .isEqualTo("{\"code\":403,\"message\":\"Missing permissions: user:list\",\"data\":null}");
    }

    @Test
    @DisplayName("创建用户：响应的 role / status 只含传入的 id，未传时键缺省")
    void createUserShape() throws Exception {
        String email = "c-" + UUID.randomUUID() + "@example.com";
        AuthApi.Response created = api.call(
                "POST",
                "/api/v1/users",
                Map.of(
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "firstName",
                        "F",
                        "lastName",
                        "L",
                        "role",
                        Map.of("id", 2)),
                admin);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.data().path("role").toString()).isEqualTo("{\"id\":2}");
        assertThat(created.data().has("status")).isFalse();
        assertThat(created.data().path("gender").asInt()).isZero();
        assertThat(created.data().path("provider").asString()).isEqualTo("email");

        AuthApi.Response duplicate =
                api.call("POST", "/api/v1/users", Map.of("email", email, "password", PASSWORD), admin);
        assertThat(duplicate.status()).isEqualTo(422);

        AuthApi.Response badRole = api.call(
                "POST",
                "/api/v1/users",
                Map.of("email", "r-" + email, "password", PASSWORD, "role", Map.of("id", 9)),
                admin);
        assertThat(badRole.status()).isEqualTo(422);
        assertThat(badRole.body().path("data").has("role")).isTrue();
    }

    @Test
    @DisplayName("用户列表：分页、按角色过滤、排序与 hasNextPage")
    void listUsers() throws Exception {
        String filters = URLEncoder.encode("{\"roles\":[{\"id\":1}]}", StandardCharsets.UTF_8);
        AuthApi.Response admins = api.call("GET", "/api/v1/users?filters=" + filters, null, admin);
        assertThat(admins.status()).as(admins.body().toString()).isEqualTo(200);
        assertThat(new ArrayList<>(admins.data().propertyNames())).containsExactly("data", "hasNextPage");
        for (JsonNode user : admins.data().path("data")) {
            assertThat(user.path("role").path("id").asInt()).isEqualTo(1);
        }

        String sort = URLEncoder.encode("[{\"orderBy\":\"id\",\"order\":\"desc\"}]", StandardCharsets.UTF_8);
        AuthApi.Response page = api.call("GET", "/api/v1/users?page=1&limit=1&sort=" + sort, null, admin);
        assertThat(page.data().path("data")).hasSize(1);
        assertThat(page.data().path("hasNextPage").asBoolean()).isTrue();

        AuthApi.Response far = api.call("GET", "/api/v1/users?page=10000000000&limit=10000000000", null, admin);
        assertThat(far.status()).isEqualTo(200);
        assertThat(far.data().path("data")).isEmpty();
        assertThat(far.data().path("hasNextPage").asBoolean()).isFalse();

        AuthApi.Response invalid = api.call("GET", "/api/v1/users?page=0", null, admin);
        assertThat(invalid.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("管理员读取、更新、删除用户；非法 id 400，不存在 data 为 null")
    void readUpdateDelete() throws Exception {
        String email = "m-" + UUID.randomUUID() + "@example.com";
        int id = api.call("POST", "/api/v1/users", Map.of("email", email, "password", PASSWORD), admin)
                .data()
                .path("id")
                .asInt();

        AuthApi.Response read = api.call("GET", "/api/v1/users/" + id, null, admin);
        assertThat(read.data().path("email").asString()).isEqualTo(email);

        AuthApi.Response updated = api.call("PUT", "/api/v1/users/" + id, Map.of("firstName", "Up"), admin);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data().path("firstName").asString()).isEqualTo("Up");
        assertThat(updated.data().path("role").isNull()).isTrue();

        assertThat(api.call("DELETE", "/api/v1/users/" + id, null, admin).status())
                .isEqualTo(200);
        AuthApi.Response gone = api.call("GET", "/api/v1/users/" + id, null, admin);
        assertThat(gone.status()).isEqualTo(200);
        assertThat(gone.body().path("data").isNull()).isTrue();

        assertThat(api.call("GET", "/api/v1/users/abc", null, admin).status()).isEqualTo(400);
        AuthApi.Response missing = api.call("PUT", "/api/v1/users/" + id, Map.of("firstName", "X"), admin);
        assertThat(missing.status()).isEqualTo(404);
    }
}
