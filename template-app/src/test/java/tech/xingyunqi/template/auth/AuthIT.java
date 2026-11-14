package tech.xingyunqi.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.xingyunqi.template.auth.AuthApi.ADMIN;
import static tech.xingyunqi.template.auth.AuthApi.JOHN;
import static tech.xingyunqi.template.auth.AuthApi.JSON;
import static tech.xingyunqi.template.auth.AuthApi.PASSWORD;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tech.xingyunqi.template.common.security.route.AuthProperties;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Jwt jwt;

    @Autowired
    AuthProperties properties;

    @Autowired
    Clock clock;

    @Autowired
    tech.xingyunqi.template.common.redis.Cache cache;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    tech.xingyunqi.template.integrations.mail.Mailer mailer;

    AuthApi api;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
    }

    String registerActiveUser() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        AuthApi.Response registered = api.call(
                "POST",
                "/api/v1/auth/email/register",
                Map.of("email", email, "password", PASSWORD, "firstName", "A", "lastName", "B"),
                null);
        assertThat(registered.status()).isEqualTo(200);
        jdbc.update("update \"user\" set \"statusId\" = 1 where email = ?", email);
        return email;
    }

    @Test
    @DisplayName("登录响应 data 键顺序为 refreshToken、token、tokenExpires、user，access 令牌 role 为完整角色")
    void loginResponseShape() throws Exception {
        AuthApi.Response response = api.login(ADMIN, PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("code").asInt()).isEqualTo(200);
        List<String> keys = new ArrayList<>(response.data().propertyNames());
        assertThat(keys).containsExactly("refreshToken", "token", "tokenExpires", "user");
        JsonNode user = response.data().path("user");
        assertThat(new ArrayList<>(user.propertyNames()))
                .containsExactly(
                        "id",
                        "email",
                        "provider",
                        "firstName",
                        "lastName",
                        "phone",
                        "nickname",
                        "gender",
                        "birthday",
                        "photo",
                        "role",
                        "status",
                        "createdAt",
                        "updatedAt",
                        "deletedAt",
                        "wechatOpenId",
                        "wechatUnionId",
                        "alipayOpenId");
        assertThat(user.path("role").toString()).isEqualTo("{\"id\":1,\"name\":\"Admin\",\"__entity\":\"RoleEntity\"}");
        assertThat(user.path("status").toString())
                .isEqualTo("{\"id\":1,\"name\":\"Active\",\"__entity\":\"StatusEntity\"}");
        JsonNode access = AuthApi.payload(response.data().path("token").asString());
        assertThat(new ArrayList<>(access.propertyNames())).containsExactly("id", "role", "sessionId", "iat", "exp");
        assertThat(access.path("role").toString())
                .isEqualTo("{\"id\":1,\"name\":\"Admin\",\"__entity\":\"RoleEntity\"}");
        assertThat(access.path("exp").asLong() - access.path("iat").asLong()).isEqualTo(15 * 60);
        long expires = response.data().path("tokenExpires").asLong();
        assertThat(expires - clock.millis()).isBetween(14 * 60_000L, 15 * 60_000L);
    }

    @Test
    @DisplayName("邮箱不存在与密码错误按字段返回 422")
    void loginErrors() throws Exception {
        AuthApi.Response missing = api.login("nobody@example.com", PASSWORD);
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().path("data").path("email").asString()).isNotEmpty();

        AuthApi.Response wrong = api.login(ADMIN, AuthApi.md5("wrong"));
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.body().path("data").path("password").asString()).isNotEmpty();
    }

    @Test
    @DisplayName("大写 MD5 密码同样可以登录")
    void uppercaseMd5Accepted() throws Exception {
        assertThat(api.login(ADMIN, PASSWORD.toUpperCase(java.util.Locale.ROOT)).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("公开的登录接口忽略过期的 access 令牌")
    void publicRouteIgnoresExpiredToken() throws Exception {
        String expired = jwt.sign(Map.of("id", 1, "sessionId", 1), properties.secret(), "-1s");
        AuthApi.Response response = api.call(
                "POST",
                "/api/v1/auth/email/login",
                JSON.createObjectNode().put("email", ADMIN).put("password", PASSWORD),
                expired);
        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("auth/me 无令牌 401，令牌有效返回当前用户")
    void me() throws Exception {
        AuthApi.Response anonymous = api.call("GET", "/api/v1/auth/me", null, null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body().toString()).isEqualTo("{\"code\":401,\"message\":\"Unauthorized\",\"data\":null}");

        String token = api.loginData(JOHN).path("token").asString();
        AuthApi.Response me = api.call("GET", "/api/v1/auth/me", null, token);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.data().path("email").asString()).isEqualTo(JOHN);
    }

    @Test
    @DisplayName("刷新令牌轮换：新 access 令牌的 role 为 {id}，旧刷新令牌 60 秒内重放只返回 401，会话仍可用")
    void refreshRotatesAndToleratesConcurrentStaleRequest() throws Exception {
        String email = registerActiveUser();
        JsonNode login = api.loginData(email);
        String h0 = login.path("refreshToken").asString();

        AuthApi.Response refreshed = api.call("POST", "/api/v1/auth/refresh", null, h0);
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(new ArrayList<>(refreshed.data().propertyNames()))
                .containsExactly("token", "refreshToken", "tokenExpires");
        assertThat(AuthApi.payload(refreshed.data().path("token").asString())
                        .path("role")
                        .toString())
                .isEqualTo("{\"id\":2}");

        AuthApi.Response stale = api.call("POST", "/api/v1/auth/refresh", null, h0);
        assertThat(stale.status()).isEqualTo(401);

        String h1 = refreshed.data().path("refreshToken").asString();
        assertThat(api.call("POST", "/api/v1/auth/refresh", null, h1).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("两次轮换后重放最早的刷新令牌：401 且会话被吊销")
    void replayRevokesSession() throws Exception {
        String email = registerActiveUser();
        JsonNode login = api.loginData(email);
        String access = login.path("token").asString();
        String h0 = login.path("refreshToken").asString();
        String h1 = api.call("POST", "/api/v1/auth/refresh", null, h0)
                .data()
                .path("refreshToken")
                .asString();
        String h2 = api.call("POST", "/api/v1/auth/refresh", null, h1)
                .data()
                .path("refreshToken")
                .asString();

        assertThat(api.call("POST", "/api/v1/auth/refresh", null, h0).status()).isEqualTo(401);

        assertThat(api.call("POST", "/api/v1/auth/refresh", null, h2).status()).isEqualTo(401);
        AuthApi.Response me = api.call("GET", "/api/v1/auth/me", null, access);
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.body().path("message").asString()).isEqualTo("Session expired");
    }

    @Test
    @DisplayName("logout 后 access 令牌立即失效")
    void logoutRevokesAccessToken() throws Exception {
        String token = api.loginData(registerActiveUser()).path("token").asString();
        assertThat(api.call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(200);

        AuthApi.Response logout = api.call("POST", "/api/v1/auth/logout", null, token);
        assertThat(logout.status()).isEqualTo(200);
        assertThat(logout.body().toString()).isEqualTo("{\"code\":200,\"message\":\"success\"}");
        assertThat(api.call("GET", "/api/v1/auth/me", null, token)
                        .body()
                        .path("message")
                        .asString())
                .isEqualTo("Session expired");
    }

    @Test
    @DisplayName("注册后为 inactive，可登录；确认邮箱后变为 active，重复确认 422")
    void registerAndConfirm() throws Exception {
        String email = "reg-" + UUID.randomUUID() + "@example.com";
        AuthApi.Response registered = api.call(
                "POST",
                "/api/v1/auth/email/register",
                Map.of("email", email, "password", PASSWORD, "firstName", "A", "lastName", "B"),
                null);
        assertThat(registered.body().toString()).isEqualTo("{\"code\":200,\"message\":\"success\"}");
        AuthApi.Response login = api.login(email, PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.data().path("user").path("status").path("id").asInt()).isEqualTo(2);

        int id = login.data().path("user").path("id").asInt();
        String hash = jwt.sign(Map.of("confirmEmailUserId", id), properties.confirmEmailSecret(), "1d");
        assertThat(api.call("POST", "/api/v1/auth/email/confirm", Map.of("hash", hash), null)
                        .status())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("select \"statusId\" from \"user\" where id = ?", Integer.class, id))
                .isEqualTo(1);
        AuthApi.Response again = api.call("POST", "/api/v1/auth/email/confirm", Map.of("hash", hash), null);
        assertThat(again.status()).isEqualTo(422);

        AuthApi.Response duplicate = api.call(
                "POST",
                "/api/v1/auth/email/register",
                Map.of("email", email, "password", PASSWORD, "firstName", "A", "lastName", "B"),
                null);
        assertThat(duplicate.status()).isEqualTo(422);
        assertThat(duplicate.body().path("data").path("email").asString()).isNotEmpty();
    }

    @Test
    @DisplayName("确认邮箱令牌过期为 tokenExpired，签名错误为 invalidHash")
    void confirmHashErrors() throws Exception {
        String expired = jwt.sign(Map.of("confirmEmailUserId", 1), properties.confirmEmailSecret(), "-1s");
        AuthApi.Response expiredResponse =
                api.call("POST", "/api/v1/auth/email/confirm", Map.of("hash", expired), null);
        assertThat(expiredResponse.status()).isEqualTo(422);

        String forged = jwt.sign(Map.of("confirmEmailUserId", 1), "other-secret", "1d");
        AuthApi.Response forgedResponse = api.call("POST", "/api/v1/auth/email/confirm", Map.of("hash", forged), null);
        assertThat(forgedResponse.status()).isEqualTo(422);
        assertThat(forgedResponse.body().path("data").path("hash").asString())
                .isNotEqualTo(expiredResponse.body().path("data").path("hash").asString());
    }

    @Test
    @DisplayName("找回密码后重置：旧会话全部失效，同一重置令牌只能用一次")
    void resetPasswordInvalidatesSessionsAndToken() throws Exception {
        String email = registerActiveUser();
        String token = api.loginData(email).path("token").asString();
        assertThat(api.call("POST", "/api/v1/auth/forgot/password", Map.of("email", email), null)
                        .status())
                .isEqualTo(200);
        assertThat(api.call("POST", "/api/v1/auth/forgot/password", Map.of("email", "none@example.com"), null)
                        .status())
                .isEqualTo(422);

        int id = jdbc.queryForObject("select id from \"user\" where email = ?", Integer.class, email);
        String stored = jdbc.queryForObject("select password from \"user\" where id = ?", String.class, id);
        String fingerprint = HexFormatSupport.fingerprint(stored);
        String hash = jwt.sign(Map.of("forgotUserId", id, "pwd", fingerprint), properties.forgotSecret(), "30m");
        String newPassword = AuthApi.md5("NewSecret01");

        AuthApi.Response reset =
                api.call("POST", "/api/v1/auth/reset/password", Map.of("hash", hash, "password", newPassword), null);
        assertThat(reset.status()).isEqualTo(200);
        assertThat(api.call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(401);
        assertThat(api.login(email, newPassword).status()).isEqualTo(200);

        AuthApi.Response reused =
                api.call("POST", "/api/v1/auth/reset/password", Map.of("hash", hash, "password", PASSWORD), null);
        assertThat(reused.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("改密：旧密码错误 422；成功后其它会话失效，当前会话保留")
    void changePassword() throws Exception {
        String email = registerActiveUser();
        String other = api.loginData(email).path("token").asString();
        String current = api.loginData(email).path("token").asString();

        AuthApi.Response wrong = api.call(
                "PUT",
                "/api/v1/auth/password",
                Map.of("oldPassword", AuthApi.md5("bad"), "newPassword", AuthApi.md5("Next0001")),
                current);
        assertThat(wrong.status()).isEqualTo(422);

        AuthApi.Response changed = api.call(
                "PUT",
                "/api/v1/auth/password",
                Map.of("oldPassword", PASSWORD, "newPassword", AuthApi.md5("Next0001")),
                current);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(api.call("GET", "/api/v1/auth/me", null, current).status()).isEqualTo(200);
        assertThat(api.call("GET", "/api/v1/auth/me", null, other).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("会话列表标记当前会话；下线其它会话；下线他人会话 404")
    void sessions() throws Exception {
        String email = registerActiveUser();
        String first = api.loginData(email).path("token").asString();
        String second = api.loginData(email).path("token").asString();

        AuthApi.Response list = api.call("GET", "/api/v1/auth/sessions", null, second);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.data()).hasSize(2);
        assertThat(new ArrayList<>(list.data().get(0).propertyNames()))
                .containsExactly("id", "userAgent", "ip", "createdAt", "lastActiveAt", "current");
        long currentCount = 0;
        for (JsonNode session : list.data()) {
            if (session.path("current").asBoolean()) {
                currentCount++;
                assertThat(session.path("id").asInt())
                        .isEqualTo(AuthApi.payload(second).path("sessionId").asInt());
            }
        }
        assertThat(currentCount).isEqualTo(1);

        int adminSession = AuthApi.payload(api.loginData(ADMIN).path("token").asString())
                .path("sessionId")
                .asInt();
        AuthApi.Response foreign = api.call("DELETE", "/api/v1/auth/sessions/" + adminSession, null, second);
        assertThat(foreign.status()).isEqualTo(404);

        assertThat(api.call("POST", "/api/v1/auth/sessions/revoke-others", null, second)
                        .status())
                .isEqualTo(200);
        assertThat(api.call("GET", "/api/v1/auth/me", null, first).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/v1/auth/sessions", null, second).data())
                .hasSize(1);
    }

    @Test
    @DisplayName("更新个人资料：普通字段直接更新；改邮箱要求旧密码且不立即生效")
    void updateMe() throws Exception {
        String email = registerActiveUser();
        String token = api.loginData(email).path("token").asString();

        AuthApi.Response updated = api.call("PUT", "/api/v1/auth/me", Map.of("nickname", "Nick"), token);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data().path("nickname").asString()).isEqualTo("Nick");

        AuthApi.Response missingOld = api.call("PUT", "/api/v1/auth/me", Map.of("email", "new-" + email), token);
        assertThat(missingOld.status()).isEqualTo(422);
        assertThat(missingOld.body().path("data").has("oldPassword")).isTrue();

        AuthApi.Response pending =
                api.call("PUT", "/api/v1/auth/me", Map.of("email", "new-" + email, "oldPassword", PASSWORD), token);
        assertThat(pending.status()).isEqualTo(200);
        assertThat(pending.data().path("email").asString()).isEqualTo(email);
    }

    @Test
    @DisplayName("注销账号后会话失效，邮箱释放")
    void deleteAccount() throws Exception {
        String email = registerActiveUser();
        String token = api.loginData(email).path("token").asString();

        AuthApi.Response deleted = api.call("DELETE", "/api/v1/auth/me", Map.of("oldPassword", PASSWORD), token);
        assertThat(deleted.status()).isEqualTo(200);
        assertThat(api.call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from \"user\" where email = ?", Integer.class, email))
                .isZero();
    }

    @Test
    @DisplayName("argon2id 参数不同的旧哈希登录后被重新哈希，updatedAt 不变")
    void outdatedHashIsUpgradedOnLogin() throws Exception {
        String email = registerActiveUser();
        String bcrypt = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10).encode(PASSWORD);
        jdbc.update("update \"user\" set password = ? where email = ?", bcrypt, email);
        Object updatedAt =
                jdbc.queryForObject("select \"updatedAt\" from \"user\" where email = ?", Object.class, email);

        assertThat(api.login(email, PASSWORD).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select password from \"user\" where email = ?", String.class, email))
                .startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(jdbc.queryForObject("select \"updatedAt\" from \"user\" where email = ?", Object.class, email))
                .isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("确认新邮箱：令牌不含 newEmail 时邮箱不变、inactive 改为 active；含 newEmail 时改为新邮箱")
    void confirmNewEmail() throws Exception {
        String email = "cn-" + UUID.randomUUID() + "@example.com";
        api.call(
                "POST",
                "/api/v1/auth/email/register",
                Map.of("email", email, "password", PASSWORD, "firstName", "A", "lastName", "B"),
                null);
        int id = jdbc.queryForObject("select id from \"user\" where email = ?", Integer.class, email);

        String withoutEmail = jwt.sign(Map.of("confirmEmailUserId", id), properties.confirmEmailSecret(), "1d");
        assertThat(api.call("POST", "/api/v1/auth/email/confirm/new", Map.of("hash", withoutEmail), null)
                        .status())
                .isEqualTo(200);
        assertThat(jdbc.queryForMap("select email, \"statusId\" from \"user\" where id = ?", id))
                .containsEntry("email", email)
                .containsEntry("statusId", 1);

        String next = "next-" + email;
        String withEmail =
                jwt.sign(Map.of("confirmEmailUserId", id, "newEmail", next), properties.confirmEmailSecret(), "1d");
        assertThat(api.call("POST", "/api/v1/auth/email/confirm/new", Map.of("hash", withEmail), null)
                        .status())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("select email from \"user\" where id = ?", String.class, id))
                .isEqualTo(next);
    }

    @Test
    @DisplayName("用 access 令牌调用 refresh 返回 401")
    void refreshRejectsAccessToken() throws Exception {
        String access = api.loginData(JOHN).path("token").asString();
        assertThat(api.call("POST", "/api/v1/auth/refresh", null, access).status())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("状态不是 active / inactive 的用户刷新返回 422 {refresh}")
    void refreshRejectsDisallowedStatus() throws Exception {
        String email = registerActiveUser();
        String refresh = api.loginData(email).path("refreshToken").asString();
        jdbc.update("update \"user\" set \"statusId\" = null where email = ?", email);

        AuthApi.Response response = api.call("POST", "/api/v1/auth/refresh", null, refresh);
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body().path("data").has("refresh")).isTrue();
    }

    @Test
    @DisplayName("同一刷新令牌并发两次：恰好一次成功，会话保持可用")
    void concurrentRefresh() throws Exception {
        String email = registerActiveUser();
        String refresh = api.loginData(email).path("refreshToken").asString();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<AuthApi.Response> call = () -> {
                start.await();
                return api.call("POST", "/api/v1/auth/refresh", null, refresh);
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            start.countDown();
            List<AuthApi.Response> responses = List.of(first.get(), second.get());
            assertThat(responses).extracting(AuthApi.Response::status).containsExactlyInAnyOrder(200, 401);
            String token = responses.stream()
                    .filter(r -> r.status() == 200)
                    .findFirst()
                    .orElseThrow()
                    .data()
                    .path("token")
                    .asString();
            assertThat(api.call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("lastActiveAt 只在距上次超过 5 分钟时更新")
    void lastActiveAtIsThrottled() throws Exception {
        String token = api.loginData(registerActiveUser()).path("token").asString();
        int sessionId = AuthApi.payload(token).path("sessionId").asInt();

        jdbc.update("update session set \"lastActiveAt\" = now() - interval '10 minutes' where id = ?", sessionId);
        cache.delete("jwt:session:" + sessionId);
        api.call("GET", "/api/v1/auth/me", null, token);
        assertThat(jdbc.queryForObject(
                        "select \"lastActiveAt\" > now() - interval '1 minute' from session where id = ?",
                        Boolean.class,
                        sessionId))
                .isTrue();

        jdbc.update("update session set \"lastActiveAt\" = now() - interval '2 minutes' where id = ?", sessionId);
        cache.delete("jwt:session:" + sessionId);
        api.call("GET", "/api/v1/auth/me", null, token);
        assertThat(jdbc.queryForObject(
                        "select \"lastActiveAt\" < now() - interval '1 minute' from session where id = ?",
                        Boolean.class,
                        sessionId))
                .isTrue();
    }

    @Test
    @DisplayName("注册成功后发出激活邮件；改密成功但新邮箱已被占用时，已删除的会话保留删除且不发邮件")
    void sideEffectsFollowCommitRules() throws Exception {
        org.mockito.Mockito.clearInvocations(mailer);
        String email = registerActiveUser();
        org.mockito.Mockito.verify(mailer)
                .send(org.mockito.ArgumentMatchers.argThat(
                        m -> m.template().equals("activation") && m.to().equals(email)));

        String other = api.loginData(email).path("token").asString();
        String current = api.loginData(email).path("token").asString();
        org.mockito.Mockito.clearInvocations(mailer);
        AuthApi.Response response = api.call(
                "PUT",
                "/api/v1/auth/me",
                Map.of("password", AuthApi.md5("Next0001"), "oldPassword", PASSWORD, "email", JOHN),
                current);

        assertThat(response.status()).isEqualTo(422);
        assertThat(api.call("GET", "/api/v1/auth/me", null, other).status()).isEqualTo(401);
        assertThat(api.call("GET", "/api/v1/auth/me", null, current).status()).isEqualTo(200);
        org.mockito.Mockito.verifyNoInteractions(mailer);
    }

    /** 与服务端相同的找回密码指纹。 */
    static final class HexFormatSupport {
        static String fingerprint(String hash) throws Exception {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        }
    }
}
