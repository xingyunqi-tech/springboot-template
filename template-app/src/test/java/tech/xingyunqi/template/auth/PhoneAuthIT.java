package tech.xingyunqi.template.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static tech.xingyunqi.template.auth.AuthApi.PASSWORD;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.integrations.sms.AliyunSmsProvider;
import tech.xingyunqi.template.modules.notifications.domain.Notification;
import tech.xingyunqi.template.support.StubServer;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = {"app.sms.mock-mode=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PhoneAuthIT {

    static final StubServer STUB = new StubServer();
    static final AtomicLong PHONES = new AtomicLong(System.nanoTime() % 100_000_000L);
    static final AtomicLong OPEN_IDS = new AtomicLong(System.nanoTime());

    @DynamicPropertySource
    static void outbound(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String privateKey = Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        registry.add("app.wechat.api-base", STUB::baseUrl);
        registry.add("app.wechat.app-id", () -> "wx-app");
        registry.add("app.wechat.app-secret", () -> "wx-app-secret");
        registry.add("app.wechat.mini-app-id", () -> "wx-mini");
        registry.add("app.wechat.mini-app-secret", () -> "wx-mini-secret");
        registry.add("app.alipay.app-id", () -> "ali-app");
        registry.add("app.alipay.private-key", () -> privateKey);
        registry.add("app.alipay.gateway", () -> STUB.baseUrl() + "/gateway.do");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Cache cache;

    @MockitoSpyBean
    AliyunSmsProvider sms;

    AuthApi api;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
        STUB.reset();
        STUB.on("/cgi-bin/stable_token", StubServer.Reply.json("{\"access_token\":\"T\",\"expires_in\":7200}"));
    }

    static String newPhone() {
        return String.format(java.util.Locale.ROOT, "137%08d", PHONES.incrementAndGet() % 100_000_000L);
    }

    static String newOpenId(String prefix) {
        return prefix + "-" + OPEN_IDS.incrementAndGet();
    }

    AuthApi.Response post(String path, Object body) throws Exception {
        return api.call("POST", path, body, null);
    }

    String code(String phone, String type) {
        JsonNode stored = cache.get("sms:code:" + type + ":" + phone);
        assertThat(stored).as("code for %s %s", type, phone).isNotNull();
        return stored.path("code").asString();
    }

    void storeCode(String phone, String type, String code) {
        cache.set(
                "sms:code:" + type + ":" + phone,
                Map.of("code", code, "attempts", 0, "createdAt", System.currentTimeMillis()),
                300_000);
    }

    AuthApi.Response register(String phone, Map<String, Object> extra) throws Exception {
        assertThat(post("/api/v1/auth/phone/send-code", Map.of("phone", phone, "type", "register"))
                        .status())
                .isEqualTo(200);
        Map<String, Object> body = new java.util.LinkedHashMap<>(extra);
        body.put("phone", phone);
        body.put("code", code(phone, "register"));
        body.put("password", PASSWORD);
        return post("/api/v1/auth/phone/register", body);
    }

    static final JsonNode MESSAGES =
            AuthApi.JSON.readTree(PhoneAuthIT.class.getResourceAsStream("/i18n/en/common.json"));

    /** 错误键按默认语言翻译后的 {@code data}。 */
    static String e(String field, String key) {
        return AuthApi.JSON
                .createObjectNode()
                .put(field, MESSAGES.path(key).asString())
                .toString();
    }

    static String errors(AuthApi.Response response) {
        assertThat(response.status()).as(response.body().toString()).isEqualTo(422);
        return response.data().toString();
    }

    void assertDefaultNotificationSettings(JsonNode user) {
        assertThat(jdbc.queryForList(
                        "select category from notification_setting where \"userId\" = ?",
                        String.class,
                        user.path("id").asInt()))
                .containsExactlyInAnyOrderElementsOf(Notification.CATEGORIES);
    }

    void stubMiniLogin(String openId) {
        STUB.on(
                "/sns/jscode2session",
                StubServer.Reply.json(
                        "{\"openid\":\"" + openId + "\",\"session_key\":\"k\",\"unionid\":\"u-" + openId + "\"}"));
    }

    void stubPhone(String phone, String countryCode) {
        STUB.on(
                "/wxa/business/getuserphonenumber",
                StubServer.Reply.json("{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"" + phone
                        + "\",\"purePhoneNumber\":\"" + phone + "\",\"countryCode\":\"" + countryCode + "\"}}"));
    }

    @Test
    @DisplayName("send-code：请求体校验、change_phone、注册已存在、登录类型手机号不存在均为 422")
    void sendCodeErrors() throws Exception {
        assertThat(post("/api/v1/auth/phone/send-code", Map.of()).status()).isEqualTo(422);
        assertThat(errors(post("/api/v1/auth/phone/send-code", Map.of("phone", "123", "type", "nope"))))
                .contains("phone")
                .contains("type");
        assertThat(errors(post("/api/v1/auth/phone/send-code", Map.of("phone", newPhone(), "type", "change_phone"))))
                .contains("\"type\"");
        String phone = newPhone();
        register(phone, Map.of());
        assertThat(errors(post("/api/v1/auth/phone/send-code", Map.of("phone", phone, "type", "register"))))
                .isEqualTo(e("phone", "phoneAlreadyExists"));
        for (String type : List.of("login", "reset_password", "verify_identity")) {
            assertThat(errors(post("/api/v1/auth/phone/send-code", Map.of("phone", newPhone(), "type", type))))
                    .isEqualTo(e("phone", "phoneNotFound"));
        }
        assertThat(errors(post("/api/v1/auth/phone/send-code", Map.of("phone", newPhone()))))
                .isEqualTo(e("phone", "phoneNotFound"));
    }

    @Test
    @DisplayName("send-code：重发间隔内为 rateLimited 并带整数 retryAfter，短信发送失败为 sendFailed")
    void sendCodeRateLimitAndFailure() throws Exception {
        String phone = newPhone();
        register(phone, Map.of());
        assertThat(post("/api/v1/auth/phone/send-code", Map.of("phone", phone))
                        .body()
                        .toString())
                .isEqualTo("{\"code\":200,\"message\":\"success\"}");
        AuthApi.Response again = post("/api/v1/auth/phone/send-code", Map.of("phone", phone, "type", "login"));
        assertThat(again.status()).isEqualTo(422);
        assertThat(again.data().path("phone").asString())
                .isEqualTo(MESSAGES.path("rateLimited").asString());
        assertThat(again.data().path("retryAfter").isIntegralNumber()).isTrue();
        assertThat(again.data().path("retryAfter").asInt()).isBetween(1, 60);

        doReturn(new AliyunSmsProvider.SendSmsResult(false, "SEND_ERROR", "boom", null, null))
                .when(sms)
                .sendSms(anyString(), anyMap());
        assertThat(errors(post("/api/v1/auth/phone/send-code", Map.of("phone", phone, "type", "reset_password"))))
                .isEqualTo(e("phone", "sendFailed"));
        assertThat(cache.get("sms:code:reset_password:" + phone)).isNull();
    }

    @Test
    @DisplayName("手机号注册：验证码错误 422，成功后直接 active 并返回令牌，关联只含 id，并写入默认通知设置")
    void registerWithPhone() throws Exception {
        String phone = newPhone();
        assertThat(errors(post(
                        "/api/v1/auth/phone/register", Map.of("phone", phone, "code", "123456", "password", PASSWORD))))
                .isEqualTo(e("code", "invalidCode"));

        AuthApi.Response registered = register(phone, Map.of("nickname", " <b>Nick</b> "));
        assertThat(registered.status()).isEqualTo(200);
        assertThat(new ArrayList<>(registered.data().propertyNames()))
                .containsExactly("refreshToken", "token", "tokenExpires", "user");
        JsonNode user = registered.data().path("user");
        assertThat(user.path("phone").asString()).isEqualTo(phone);
        assertThat(user.path("provider").asString()).isEqualTo("phone");
        assertThat(user.path("role").toString()).isEqualTo("{\"id\":2}");
        assertThat(user.path("status").toString()).isEqualTo("{\"id\":1}");
        assertThat(user.has("password")).isFalse();
        assertThat(AuthApi.payload(registered.data().path("token").asString())
                        .path("role")
                        .toString())
                .isEqualTo("{\"id\":2}");
        assertThat(cache.get("sms:code:register:" + phone)).isNull();
        assertDefaultNotificationSettings(user);

        storeCode(phone, "register", "123456");
        assertThat(errors(post(
                        "/api/v1/auth/phone/register", Map.of("phone", phone, "code", "123456", "password", PASSWORD))))
                .isEqualTo(e("phone", "phoneAlreadyExists"));
    }

    @Test
    @DisplayName("手机号密码登录：成功返回完整角色；手机号不存在、无密码、密码错误均为 incorrectPhoneOrPassword；状态为空为 userNotActive")
    void loginWithPhone() throws Exception {
        String phone = newPhone();
        register(phone, Map.of());
        AuthApi.Response login =
                post("/api/v1/auth/phone/login", Map.of("phone", phone, "password", PASSWORD.toUpperCase()));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.data().path("user").path("role").toString())
                .isEqualTo("{\"id\":2,\"name\":\"User\",\"__entity\":\"RoleEntity\"}");

        String incorrect = e("credentials", "incorrectPhoneOrPassword");
        assertThat(errors(post("/api/v1/auth/phone/login", Map.of("phone", newPhone(), "password", PASSWORD))))
                .isEqualTo(incorrect);
        assertThat(errors(post("/api/v1/auth/phone/login", Map.of("phone", phone, "password", AuthApi.md5("x")))))
                .isEqualTo(incorrect);

        String passwordless = newPhone();
        jdbc.update(
                "insert into \"user\" (phone, provider, \"roleId\", \"statusId\") values (?, 'wechat', 2, 1)",
                passwordless);
        assertThat(errors(post("/api/v1/auth/phone/login", Map.of("phone", passwordless, "password", PASSWORD))))
                .isEqualTo(incorrect);

        jdbc.update("update \"user\" set \"statusId\" = null where phone = ?", phone);
        assertThat(errors(post("/api/v1/auth/phone/login", Map.of("phone", phone, "password", PASSWORD))))
                .isEqualTo(e("phone", "userNotActive"));
    }

    @Test
    @DisplayName("短信登录：验证码错误 invalidCode，未注册手机号 notFound，成功后验证码失效")
    void loginWithSms() throws Exception {
        String phone = newPhone();
        register(phone, Map.of());
        assertThat(post("/api/v1/auth/phone/send-code", Map.of("phone", phone, "type", "login"))
                        .status())
                .isEqualTo(200);
        String code = code(phone, "login");
        assertThat(errors(post("/api/v1/auth/phone/sms-login", Map.of("phone", phone, "code", "0000"))))
                .isEqualTo(e("code", "invalidCode"));
        AuthApi.Response login = post("/api/v1/auth/phone/sms-login", Map.of("phone", phone, "code", code));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.data().path("user").path("phone").asString()).isEqualTo(phone);
        assertThat(errors(post("/api/v1/auth/phone/sms-login", Map.of("phone", phone, "code", code))))
                .isEqualTo(e("code", "invalidCode"));

        String unknown = newPhone();
        storeCode(unknown, "login", "4321");
        assertThat(errors(post("/api/v1/auth/phone/sms-login", Map.of("phone", unknown, "code", "4321"))))
                .isEqualTo(e("phone", "notFound"));
    }

    @Test
    @DisplayName("通过验证码重置密码：验证码错误、手机号不存在为 422；成功后吊销全部会话，新密码可登录")
    void resetPasswordByPhone() throws Exception {
        String phone = newPhone();
        String token = register(phone, Map.of()).data().path("token").asString();
        String fresh = AuthApi.md5("NewSecret01");

        assertThat(errors(post(
                        "/api/v1/auth/phone/reset-password",
                        Map.of("phone", phone, "code", "123456", "password", fresh))))
                .isEqualTo(e("code", "invalidCode"));
        String unknown = newPhone();
        storeCode(unknown, "reset_password", "123456");
        assertThat(errors(post(
                        "/api/v1/auth/phone/reset-password",
                        Map.of("phone", unknown, "code", "123456", "password", fresh))))
                .isEqualTo(e("phone", "phoneNotExists"));

        assertThat(post("/api/v1/auth/phone/send-code", Map.of("phone", phone, "type", "reset_password"))
                        .status())
                .isEqualTo(200);
        AuthApi.Response reset = post(
                "/api/v1/auth/phone/reset-password",
                Map.of("phone", phone, "code", code(phone, "reset_password"), "password", fresh));
        assertThat(reset.body().toString()).isEqualTo("{\"code\":200,\"message\":\"success\"}");
        assertThat(api.call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(401);
        assertThat(post("/api/v1/auth/phone/login", Map.of("phone", phone, "password", PASSWORD))
                        .status())
                .isEqualTo(422);
        assertThat(post("/api/v1/auth/phone/login", Map.of("phone", phone, "password", fresh))
                        .status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("微信小程序登录：首次登录建号（昵称取请求值）并写入默认通知设置，再次登录复用；状态为空为 userNotActive")
    void wechatMiniAppLogin() throws Exception {
        String openId = newOpenId("mini");
        stubMiniLogin(openId);
        AuthApi.Response first =
                post("/api/v1/auth/wechat/login", Map.of("code", "c1", "type", "mini_app", "nickname", "小明"));
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        JsonNode user = first.data().path("user");
        assertThat(user.path("wechatOpenId").asString()).isEqualTo(openId);
        assertThat(user.path("wechatUnionId").asString()).isEqualTo("u-" + openId);
        assertThat(user.path("nickname").asString()).isEqualTo("小明");
        assertThat(user.path("provider").asString()).isEqualTo("wechat");
        assertThat(user.path("gender").asInt()).isZero();
        assertThat(user.path("role").toString()).isEqualTo("{\"id\":2}");
        assertThat(STUB.requests("/sns/jscode2session").getFirst().query()).contains("js_code=c1");
        assertDefaultNotificationSettings(user);

        AuthApi.Response second = post("/api/v1/auth/wechat/login", Map.of("code", "c2", "type", "mini_app"));
        assertThat(second.data().path("user").path("id").asInt())
                .isEqualTo(user.path("id").asInt());
        assertThat(second.data().path("user").path("role").path("name").asString())
                .isEqualTo("User");

        jdbc.update("update \"user\" set \"statusId\" = null where \"wechatOpenId\" = ?", openId);
        assertThat(errors(post("/api/v1/auth/wechat/login", Map.of("code", "c3", "type", "mini_app"))))
                .isEqualTo(e("wechat", "userNotActive"));
    }

    @Test
    @DisplayName("微信 APP 登录取用户信息中的昵称与性别并写入默认通知设置；code 无效为 invalidCode 并带 wechatError")
    void wechatAppLogin() throws Exception {
        String openId = newOpenId("app");
        STUB.on(
                "/sns/oauth2/access_token",
                StubServer.Reply.json("{\"access_token\":\"at\",\"openid\":\"" + openId + "\"}"));
        STUB.on(
                "/sns/userinfo",
                StubServer.Reply.json(
                        "{\"openid\":\"" + openId + "\",\"nickname\":\"WX\",\"sex\":2,\"unionid\":\"un\"}"));
        AuthApi.Response login = post("/api/v1/auth/wechat/login", Map.of("code", "c", "type", "app"));
        assertThat(login.status()).as(login.body().toString()).isEqualTo(200);
        assertThat(login.data().path("user").path("nickname").asString()).isEqualTo("WX");
        assertThat(login.data().path("user").path("gender").asInt()).isEqualTo(2);
        assertThat(login.data().path("user").path("wechatUnionId").asString()).isEqualTo("un");
        assertDefaultNotificationSettings(login.data().path("user"));

        STUB.on("/sns/oauth2/access_token", StubServer.Reply.json("{\"errcode\":40029,\"errmsg\":\"invalid code\"}"));
        assertThat(errors(post("/api/v1/auth/wechat/login", Map.of("code", "c", "type", "app"))))
                .isEqualTo(e("code", "invalidCode").replace("}", ",\"wechatError\":\"invalid code\"}"));
        assertThat(post("/api/v1/auth/wechat/login", Map.of("code", "c", "type", "web"))
                        .status())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("微信手机号登录：新建用户（写入默认通知设置）、给已有 openId 用户绑定手机号、给已有手机号用户绑定微信")
    void wechatPhoneLoginBindsAndCreates() throws Exception {
        String openId = newOpenId("phone");
        String phone = newPhone();
        stubMiniLogin(openId);
        stubPhone(phone, "86");
        AuthApi.Response created = post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
        assertThat(created.data().path("user").path("phone").asString()).isEqualTo(phone);
        assertThat(created.data().path("user").path("nickname").asString()).isEqualTo("微信用户");
        assertThat(created.data().path("user").path("role").toString()).isEqualTo("{\"id\":2}");
        assertThat(STUB.requests("/wxa/business/getuserphonenumber").getFirst().body())
                .isEqualTo("{\"code\":\"p\"}");
        assertDefaultNotificationSettings(created.data().path("user"));

        String boundOpenId = newOpenId("bind");
        stubMiniLogin(boundOpenId);
        assertThat(post("/api/v1/auth/wechat/login", Map.of("code", "c", "type", "mini_app"))
                        .status())
                .isEqualTo(200);
        String newPhone = newPhone();
        stubPhone(newPhone, "86");
        AuthApi.Response bindPhone =
                post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"));
        assertThat(bindPhone.data().path("user").path("phone").asString()).isEqualTo(newPhone);
        assertThat(bindPhone.data().path("user").path("wechatOpenId").asString())
                .isEqualTo(boundOpenId);
        assertThat(bindPhone.data().path("user").path("role").path("name").asString())
                .isEqualTo("User");

        String phoneUser = newPhone();
        register(phoneUser, Map.of());
        String wechatForPhone = newOpenId("phone-user");
        stubMiniLogin(wechatForPhone);
        stubPhone(phoneUser, "86");
        AuthApi.Response bindWechat =
                post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"));
        assertThat(bindWechat.data().path("user").path("provider").asString()).isEqualTo("phone");
        assertThat(bindWechat.data().path("user").path("wechatOpenId").asString())
                .isEqualTo(wechatForPhone);
        assertThat(bindWechat.data().path("user").path("wechatUnionId").asString())
                .isEqualTo("u-" + wechatForPhone);

        AuthApi.Response again = post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"));
        assertThat(again.data().path("user").path("id").asInt())
                .isEqualTo(bindWechat.data().path("user").path("id").asInt());
    }

    @Test
    @DisplayName("微信手机号登录：手机号已绑定其他微信、非大陆号、手机号 code 无效均为 422")
    void wechatPhoneLoginErrors() throws Exception {
        String phone = newPhone();
        stubMiniLogin(newOpenId("owner"));
        stubPhone(phone, "86");
        assertThat(post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"))
                        .status())
                .isEqualTo(200);

        stubMiniLogin(newOpenId("other"));
        AuthApi.Response bound = post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"));
        assertThat(errors(bound)).isEqualTo(e("phone", "boundToOtherWechat"));
        assertThat(bound.body().path("message").asString()).isNotEmpty();

        String otherOpenId = newOpenId("withphone");
        stubMiniLogin(otherOpenId);
        String ownPhone = newPhone();
        stubPhone(ownPhone, "86");
        post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"));
        stubPhone(phone, "86");
        assertThat(errors(post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"))))
                .isEqualTo(e("phone", "boundToOtherWechat"));

        stubPhone(newPhone(), "1");
        assertThat(errors(post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"))))
                .isEqualTo(e("phone", "unsupportedRegion"));

        STUB.on("/wxa/business/getuserphonenumber", StubServer.Reply.json("{\"errcode\":40029,\"errmsg\":\"bad\"}"));
        assertThat(errors(post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"))))
                .isEqualTo(e("phoneCode", "invalidCode"));

        stubPhone(newPhone(), "86");
        STUB.on("/sns/jscode2session", StubServer.Reply.json("{\"errcode\":40163,\"errmsg\":\"used\"}"));
        assertThat(errors(post("/api/v1/auth/wechat/phone-login", Map.of("loginCode", "l", "phoneCode", "p"))))
                .isEqualTo(e("code", "invalidCode").replace("}", ",\"wechatError\":\"used\"}"));
    }

    @Test
    @DisplayName("支付宝登录：首次建号（昵称“支付宝用户”）并写入默认通知设置，再次登录复用；授权码无效为 invalidCode")
    void alipayLogin() throws Exception {
        String openId = newOpenId("ali");
        STUB.on(
                "/gateway.do",
                StubServer.Reply.json("{\"alipay_system_oauth_token_response\":{\"open_id\":\"" + openId
                        + "\",\"access_token\":\"t\"}}"));
        AuthApi.Response first = post("/api/v1/auth/alipay/login", Map.of("authCode", "a1"));
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        JsonNode user = first.data().path("user");
        assertThat(user.path("alipayOpenId").asString()).isEqualTo(openId);
        assertThat(user.path("nickname").asString()).isEqualTo("支付宝用户");
        assertThat(user.path("provider").asString()).isEqualTo("alipay");
        assertThat(user.path("role").toString()).isEqualTo("{\"id\":2}");
        assertDefaultNotificationSettings(user);

        AuthApi.Response second = post("/api/v1/auth/alipay/login", Map.of("authCode", "a2"));
        assertThat(second.data().path("user").path("id").asInt())
                .isEqualTo(user.path("id").asInt());
        assertThat(second.data().path("user").path("status").path("name").asString())
                .isEqualTo("Active");

        jdbc.update("update \"user\" set \"statusId\" = null where \"alipayOpenId\" = ?", openId);
        assertThat(errors(post("/api/v1/auth/alipay/login", Map.of("authCode", "a3"))))
                .isEqualTo(e("alipay", "userNotActive"));

        STUB.on(
                "/gateway.do",
                StubServer.Reply.json("{\"error_response\":{\"code\":\"40002\",\"sub_code\":\"isv.code-invalid\"}}"));
        assertThat(errors(post("/api/v1/auth/alipay/login", Map.of("authCode", "bad"))))
                .isEqualTo(e("authCode", "invalidCode"));
        assertThat(post("/api/v1/auth/alipay/login", Map.of()).status()).isEqualTo(422);
    }
}
