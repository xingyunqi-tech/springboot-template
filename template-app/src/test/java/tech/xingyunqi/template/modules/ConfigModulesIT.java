package tech.xingyunqi.template.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.xingyunqi.template.auth.AuthApi.ADMIN;
import static tech.xingyunqi.template.auth.AuthApi.JOHN;

import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;

/** 配置、地区、站点信息与协议同意接口。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConfigModulesIT {

    @Autowired
    MockMvc mvc;

    AuthApi api;
    String admin;
    String john;

    @BeforeEach
    void setUp() throws Exception {
        api = new AuthApi(mvc);
        admin = api.loginData(ADMIN).path("token").asString();
        john = api.loginData(JOHN).path("token").asString();
    }

    String register() throws Exception {
        String email = "consent-" + UUID.randomUUID() + "@example.com";
        AuthApi.Response registered = api.call(
                "POST",
                "/api/v1/auth/email/register",
                Map.of("email", email, "password", AuthApi.PASSWORD, "firstName", "C", "lastName", "T"),
                null);
        assertThat(registered.status()).as(registered.body().toString()).isEqualTo(200);
        return api.loginData(email).path("token").asString();
    }

    @Test
    @DisplayName("公开配置：登记项未写入时返回默认值且时间为 null；非公开、未登记与无默认值的为 404 configNotFound")
    void publicConfig() throws Exception {
        AuthApi.Response enabled = api.call("GET", "/api/v1/config/ai.enabled", null, null);
        assertThat(enabled.status()).isEqualTo(200);
        assertThat(enabled.data().path("value").toString()).isEqualTo("{\"value\":true}");
        assertThat(enabled.data().path("isPublic").asBoolean()).isTrue();

        AuthApi.Response trial = api.call("GET", "/api/v1/config/agreement.aiService", null, null);
        assertThat(trial.status()).isEqualTo(404);
        for (String key : List.of("ai.ratePerMinute", "unknown.key", "%E4%B8%AD")) {
            AuthApi.Response missing = api.call("GET", "/api/v1/config/" + key, null, null);
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.body().toString())
                    .isEqualTo("{\"code\":404,\"message\":\"Config not found\",\"data\":null}");
        }
    }

    @Test
    @DisplayName("管理员读取：未登录 401，缺少 system:config 403，未写入的登记项返回默认值")
    void adminConfig() throws Exception {
        assertThat(api.call("GET", "/api/v1/config/admin/ai.enabled", null, null)
                        .status())
                .isEqualTo(401);
        assertThat(api.call("GET", "/api/v1/config/admin/ai.enabled", null, john)
                        .status())
                .isEqualTo(403);
        AuthApi.Response rate = api.call("GET", "/api/v1/config/admin/ai.ratePerMinute", null, admin);
        assertThat(rate.status()).isEqualTo(200);
        assertThat(rate.data().path("isPublic").asBoolean()).isFalse();
        AuthApi.Response model = api.call("GET", "/api/v1/config/admin/ai.model", null, admin);
        assertThat(model.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("写入：校验 422、专用登记项 422、新建默认不公开、取值不变时不刷新 updatedAt、未传 isPublic 时保持")
    void upsert() throws Exception {
        String key = "/api/v1/config/it." + UUID.randomUUID();
        assertThat(api.call("PUT", key, Map.of("value", Map.of()), null).status())
                .isEqualTo(401);
        assertThat(api.call("PUT", key, Map.of("value", Map.of()), john).status())
                .isEqualTo(403);
        AuthApi.Response invalid = api.call("PUT", key, Map.of("value", "x", "isPublic", "yes"), admin);
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.data().propertyNames()).containsExactlyInAnyOrder("value", "isPublic");
        AuthApi.Response dedicated = api.call("PUT", "/api/v1/config/app.profile", Map.of("value", Map.of()), admin);
        assertThat(dedicated.status()).isEqualTo(422);
        assertThat(dedicated.data().has("key")).isTrue();

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", 1);
        value.put("a", Map.of("x", 1));
        AuthApi.Response created = api.call("PUT", key, Map.of("value", value), admin);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.data().path("value").toString()).isEqualTo("{\"b\":1,\"a\":{\"x\":1}}");
        assertThat(created.data().path("isPublic").asBoolean()).isFalse();
        assertThat(api.call("GET", key, null, null).status()).isEqualTo(404);

        AuthApi.Response same = api.call("PUT", key, Map.of("value", Map.of("a", Map.of("x", 1.0), "b", 1)), admin);
        assertThat(same.data().path("updatedAt")).isEqualTo(created.data().path("updatedAt"));

        AuthApi.Response published = api.call("PUT", key, Map.of("value", Map.of("c", true), "isPublic", true), admin);
        assertThat(published.data().path("isPublic").asBoolean()).isTrue();
        AuthApi.Response read = api.call("GET", key, null, null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.data().path("value").toString()).isEqualTo("{\"c\":true}");

        AuthApi.Response kept = api.call("PUT", key, Map.of("value", Map.of("c", false)), admin);
        assertThat(kept.data().path("isPublic").asBoolean()).isTrue();
        assertThat(api.call("GET", key, null, null).data().path("value").toString())
                .isEqualTo("{\"c\":false}");
    }

    @Test
    @DisplayName("登记项写入时 isPublic 以登记表为准")
    void registeredVisibility() throws Exception {
        AuthApi.Response written = api.call(
                "PUT",
                "/api/v1/config/practice.dailySetSize",
                Map.of("value", Map.of("value", 12), "isPublic", true),
                admin);
        assertThat(written.data().path("isPublic").asBoolean()).isFalse();
        assertThat(api.call("GET", "/api/v1/config/practice.dailySetSize", null, null)
                        .status())
                .isEqualTo(404);
        assertThat(api.call("GET", "/api/v1/config/admin/practice.dailySetSize", null, admin)
                        .data()
                        .path("value")
                        .toString())
                .isEqualTo("{\"value\":12}");
    }

    @Test
    @DisplayName("地区：省市区列表、按编码、树与查询；编码与查询参数校验 422，不存在的编码 404")
    void regions() throws Exception {
        JsonNode provinces =
                api.call("GET", "/api/v1/regions/provinces", null, null).data();
        assertThat(provinces.path(0).path("code").asString()).isEqualTo("110000000000");
        assertThat(new java.util.ArrayList<>(provinces.path(0).propertyNames()))
                .containsExactly("id", "code", "name", "level", "parentCode", "sort", "createdAt", "updatedAt");
        assertThat(provinces.path(0).path("parentCode").isNull()).isTrue();

        JsonNode cities = api.call("GET", "/api/v1/regions/cities/110000000000", null, null)
                .data();
        assertThat(cities.path(0).path("parentCode").asString()).isEqualTo("110000000000");
        AuthApi.Response badCity = api.call("GET", "/api/v1/regions/cities/11", null, null);
        assertThat(badCity.status()).isEqualTo(422);
        assertThat(badCity.data().has("provinceCode")).isTrue();
        assertThat(api.call("GET", "/api/v1/regions/districts/110100000000", null, null)
                        .data()
                        .size())
                .isPositive();
        assertThat(api.call("GET", "/api/v1/regions/districts/abc", null, null).status())
                .isEqualTo(422);

        AuthApi.Response byCode = api.call("GET", "/api/v1/regions/code/110101000000", null, null);
        assertThat(byCode.data().path("level").asInt()).isEqualTo(3);
        AuthApi.Response missing = api.call("GET", "/api/v1/regions/code/999999999998", null, null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body().path("message").asString()).isEqualTo("Region with code 999999999998 not found");
        assertThat(api.call("GET", "/api/v1/regions/code/x", null, null).status())
                .isEqualTo(422);

        JsonNode tree = api.call("GET", "/api/v1/regions/tree", null, null).data();
        assertThat(tree.path(0)
                        .path("children")
                        .path(0)
                        .path("children")
                        .path(0)
                        .path("children"))
                .isEmpty();

        assertThat(api.call("GET", "/api/v1/regions?keyword=%25", null, null).data())
                .isEmpty();
        JsonNode beijing =
                api.call("GET", "/api/v1/regions?keyword=%E5%8C%97", null, null).data();
        assertThat(beijing.size()).isBetween(1, 20);
        assertThat(api.call("GET", "/api/v1/regions?parentCode=110000000000", null, null)
                        .data())
                .isEqualTo(cities);
        assertThat(api.call("GET", "/api/v1/regions?level=1", null, null).data())
                .isEqualTo(provinces);
        for (String query : List.of("level=4", "level=abc", "parentCode=", "keyword=" + "x".repeat(51))) {
            assertThat(api.call("GET", "/api/v1/regions?" + query, null, null).status())
                    .as(query)
                    .isEqualTo(422);
        }
    }

    @Test
    @DisplayName("站点信息：未登录 401、缺少权限 403、校验 422；整体覆盖后公开读取到去除空白的值")
    void appProfile() throws Exception {
        AuthApi.Response initial = api.call("GET", "/api/v1/app-profile", null, null);
        assertThat(initial.status()).isEqualTo(200);
        assertThat(new java.util.ArrayList<>(initial.data().propertyNames()))
                .containsExactly(
                        "appName",
                        "slogan",
                        "companyName",
                        "copyright",
                        "icpLicense",
                        "servicePhone",
                        "serviceHours",
                        "serviceEmail",
                        "shareTitle");

        assertThat(api.call("PUT", "/api/v1/admin/app-profile", Map.of(), null).status())
                .isEqualTo(401);
        assertThat(api.call("PUT", "/api/v1/admin/app-profile", Map.of(), john).status())
                .isEqualTo(403);
        AuthApi.Response invalid = api.call(
                "PUT", "/api/v1/admin/app-profile", Map.of("servicePhone", "call me", "serviceEmail", "bad"), admin);
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.data().propertyNames()).containsExactlyInAnyOrder("servicePhone", "serviceEmail");

        AuthApi.Response saved = api.call(
                "PUT",
                "/api/v1/admin/app-profile",
                Map.of("appName", "  备考  ", "slogan", "   ", "servicePhone", " +86 400 "),
                admin);
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.data().path("appName").asString()).isEqualTo("备考");
        assertThat(saved.data().path("slogan").isNull()).isTrue();
        assertThat(saved.data().path("servicePhone").asString()).isEqualTo("+86 400");
        assertThat(api.call("GET", "/api/v1/app-profile", null, null).data()).isEqualTo(saved.data());

        api.call("PUT", "/api/v1/admin/app-profile", Map.of(), admin);
        assertThat(api.call("GET", "/api/v1/app-profile", null, null)
                        .data()
                        .path("appName")
                        .isNull())
                .isTrue();
    }

    @Test
    @DisplayName("协议同意：未登录 401；未配置版本时不需要同意且同意 422；配置后同意当前版本，重复同意保留首次时间")
    void consents() throws Exception {
        String user = register();
        assertThat(api.call("GET", "/api/v1/me/consents", null, null).status()).isEqualTo(401);
        assertThat(api.call("POST", "/api/v1/me/consents", Map.of(), null).status())
                .isEqualTo(401);

        JsonNode initial = api.call("GET", "/api/v1/me/consents", null, user).data();
        assertThat(initial).hasSize(3);
        assertThat(initial.path(0).path("type").asString()).isEqualTo("PRIVACY_POLICY");
        assertThat(initial.path(2).path("type").asString()).isEqualTo("AI_SERVICE");

        AuthApi.Response invalid = api.call("POST", "/api/v1/me/consents", Map.of("type", "NOPE", "version", ""), user);
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.data().propertyNames()).containsExactlyInAnyOrder("type", "version");

        api.call("PUT", "/api/v1/config/consent.userAgreementVersion", Map.of("value", Map.of("value", "")), admin);
        AuthApi.Response outdated = api.call(
                "POST", "/api/v1/me/consents", Map.of("type", "USER_AGREEMENT", "version", "2026-10-01"), user);
        assertThat(outdated.status()).isEqualTo(422);
        assertThat(outdated.data().has("version")).isTrue();

        String version = "it-" + UUID.randomUUID();
        api.call("PUT", "/api/v1/config/consent.aiServiceVersion", Map.of("value", Map.of("value", version)), admin);
        JsonNode pending =
                api.call("GET", "/api/v1/me/consents", null, user).data().path(2);
        assertThat(pending.path("currentVersion").asString()).isEqualTo(version);
        assertThat(pending.path("needsConsent").asBoolean()).isTrue();

        AuthApi.Response agreed =
                api.call("POST", "/api/v1/me/consents", Map.of("type", "AI_SERVICE", "version", version), user);
        assertThat(agreed.status()).isEqualTo(200);
        assertThat(agreed.data().path("agreed").asBoolean()).isTrue();
        assertThat(agreed.data().path("needsConsent").asBoolean()).isFalse();
        AuthApi.Response repeated =
                api.call("POST", "/api/v1/me/consents", Map.of("type", "AI_SERVICE", "version", version), user);
        assertThat(repeated.data().path("agreedAt")).isEqualTo(agreed.data().path("agreedAt"));
        assertThat(api.call("GET", "/api/v1/me/consents", null, user).data().path(2))
                .isEqualTo(agreed.data());
    }
}
