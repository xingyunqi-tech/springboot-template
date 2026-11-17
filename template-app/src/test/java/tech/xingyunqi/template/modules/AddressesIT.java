package tech.xingyunqi.template.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AddressesIT {

    static final String URL = "/api/v1/addresses";
    static final Map<String, Object> BEIJING =
            Map.of("provinceCode", "110000000000", "cityCode", "110100000000", "districtCode", "110101000000");

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
        user = TestUsers.register(api, "addr");
    }

    static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    static Map<String, Object> named(String name) {
        return body(
                "name", name, "phone", "13800138000", "province", "P", "city", "C", "district", "D", "address", "x");
    }

    AuthApi.Response call(String method, String path, Object body) throws Exception {
        return api.call(method, path, body, user.token());
    }

    int create(Map<String, Object> body) throws Exception {
        AuthApi.Response created = call("POST", URL, body);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
        return created.data().path("id").asInt();
    }

    static String query(String json) {
        return URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("未登录 401")
    void requiresAuthentication() throws Exception {
        assertThat(api.call("GET", URL, null, null).status()).isEqualTo(401);
        assertThat(api.call("PUT", URL + "/1/default", null, null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("创建：字段顺序同 nestjs，首个地址自动设为默认，名称经过清洗，区划代码回填名称")
    void createAddresses() throws Exception {
        Map<String, Object> first = named(" <b>Ann</b> ");
        first.put("unknown", true);
        AuthApi.Response created = call("POST", URL, first);
        assertThat(created.status()).isEqualTo(200);
        assertThat(new ArrayList<>(created.data().propertyNames()))
                .containsExactly(
                        "id",
                        "userId",
                        "name",
                        "phone",
                        "provinceCode",
                        "cityCode",
                        "districtCode",
                        "province",
                        "city",
                        "district",
                        "address",
                        "isDefault",
                        "createdAt",
                        "updatedAt",
                        "deletedAt");
        assertThat(created.data().path("name").asString()).isEqualTo("Ann");
        assertThat(created.data().path("isDefault").asBoolean()).isTrue();
        assertThat(created.data().path("userId").asInt()).isEqualTo(user.id());
        assertThat(created.data().path("deletedAt").isNull()).isTrue();
        assertThat(created.data().path("provinceCode").isNull()).isTrue();

        Map<String, Object> coded = body("name", "Bob", "phone", "13900139000", "address", "y", "province", "ignored");
        coded.putAll(BEIJING);
        AuthApi.Response second = call("POST", URL, coded);
        assertThat(second.data().path("isDefault").asBoolean()).isFalse();
        assertThat(second.data().path("province").asString()).isEqualTo("北京市");
        assertThat(second.data().path("district").asString()).isEqualTo("东城区");

        Map<String, Object> explicit = named("Cat");
        explicit.put("isDefault", true);
        int third = create(explicit);
        AuthApi.Response defaults = call("GET", URL + "/default", null);
        assertThat(defaults.data().path("id").asInt()).isEqualTo(third);
        assertThat(jdbc.queryForObject(
                        "select count(*) from address where \"userId\" = ? and \"isDefault\"",
                        Integer.class,
                        user.id()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("创建：校验失败 422，区划代码不全 / 名称缺失 / 组合非法 400")
    void createErrors() throws Exception {
        AuthApi.Response invalid = call("POST", URL, body("name", "A", "phone", "1", "address", "x"));
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.data().has("phone")).isTrue();

        Map<String, Object> partialCodes = named("A");
        partialCodes.put("provinceCode", "110000000000");
        AuthApi.Response incomplete = call("POST", URL, partialCodes);
        assertThat(incomplete.status()).isEqualTo(400);
        assertThat(incomplete.body().path("message").asString())
                .isEqualTo("Must provide all three region codes (province, city, district) or none at all");

        Map<String, Object> blankNames = named("A");
        blankNames.put("province", null);
        AuthApi.Response missing = call("POST", URL, blankNames);
        assertThat(missing.status()).isEqualTo(422);

        Map<String, Object> wrong = body("name", "A", "phone", "13800138000", "address", "x");
        wrong.putAll(BEIJING);
        wrong.put("cityCode", "120100000000");
        AuthApi.Response combination = call("POST", URL, wrong);
        assertThat(combination.status()).isEqualTo(400);
        assertThat(combination.body().path("message").asString())
                .isEqualTo("Invalid province/city/district combination");
    }

    @Test
    @DisplayName("每个用户最多 20 个地址，超出 403")
    void addressLimit() throws Exception {
        for (int i = 0; i < 20; i++) {
            create(named("N" + i));
        }
        AuthApi.Response over = call("POST", URL, named("over"));
        assertThat(over.status()).isEqualTo(403);
        assertThat(over.body().path("message").asString()).isEqualTo("Maximum 20 addresses allowed per user");

        AuthApi.Response page = call("GET", URL + "?limit=5&page=3", null);
        assertThat(page.data().path("total").asInt()).isEqualTo(20);
        assertThat(page.data().path("totalPages").asInt()).isEqualTo(4);
        assertThat(page.data().path("list")).hasSize(5);
    }

    @Test
    @DisplayName("列表：筛选、排序、上限 50 与查询校验")
    void listAddresses() throws Exception {
        AuthApi.Response empty = call("GET", URL, null);
        assertThat(empty.data().toString())
                .isEqualTo("{\"list\":[],\"total\":0,\"page\":1,\"limit\":10,\"totalPages\":0}");
        assertThat(call("GET", URL + "/default", null).body().path("data").isNull())
                .isTrue();

        int a = create(named("a"));
        Map<String, Object> coded = body("name", "b", "phone", "13800138000", "address", "y");
        coded.putAll(BEIJING);
        int b = create(coded);

        AuthApi.Response filtered = call("GET", URL + "?filters=" + query("{\"provinceCode\":\"110000000000\"}"), null);
        assertThat(filtered.data().path("total").asInt()).isEqualTo(1);
        assertThat(filtered.data().path("list").get(0).path("id").asInt()).isEqualTo(b);

        AuthApi.Response byName = call("GET", URL + "?filters=" + query("{\"province\":\"P\",\"city\":\"\"}"), null);
        assertThat(byName.data().path("list").get(0).path("id").asInt()).isEqualTo(a);

        AuthApi.Response sorted =
                call("GET", URL + "?sort=" + query("[{\"orderBy\":\"name\",\"order\":\"desc\"}]"), null);
        assertThat(ids(sorted.data().path("list"))).containsExactly(b, a);

        AuthApi.Response capped = call("GET", URL + "?limit=60", null);
        assertThat(capped.data().path("limit").asInt()).isEqualTo(50);

        assertThat(call("GET", URL + "?page=0&limit=abc", null).status()).isEqualTo(422);
        assertThat(call("GET", URL + "?filters=" + query("{\"provinceCode\":\"x\"}"), null)
                        .status())
                .isEqualTo(422);
        assertThat(call("GET", URL + "?sort=" + query("[{\"orderBy\":\"x\",\"order\":\"up\"}]"), null)
                        .status())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("详情、更新、设为默认、删除：非本人或已删除 404，非法 id 400")
    void updateAndDelete() throws Exception {
        int first = create(named("first"));
        int second = create(named("second"));

        assertThat(call("GET", URL + "/" + first, null).data().path("name").asString())
                .isEqualTo("first");
        assertThat(call("GET", URL + "/abc", null).status()).isEqualTo(400);
        assertThat(call("GET", URL + "/999999", null).status()).isEqualTo(404);
        TestUsers.TestUser other = TestUsers.register(api, "addr-other");
        assertThat(api.call("GET", URL + "/" + first, null, other.token()).status())
                .isEqualTo(404);

        AuthApi.Response renamed = call("PUT", URL + "/" + second, body("name", "renamed", "isDefault", true));
        assertThat(renamed.data().path("name").asString()).isEqualTo("renamed");
        assertThat(renamed.data().path("isDefault").asBoolean()).isTrue();
        assertThat(call("GET", URL + "/" + first, null).data().path("isDefault").asBoolean())
                .isFalse();

        AuthApi.Response codes = call("PUT", URL + "/" + first, BEIJING);
        assertThat(codes.data().path("city").asString()).isEqualTo("市辖区");
        AuthApi.Response partial = call("PUT", URL + "/" + first, body("cityCode", "110100000000"));
        assertThat(partial.status()).isEqualTo(200);
        Map<String, Object> bad = new LinkedHashMap<>(BEIJING);
        bad.put("cityCode", "120100000000");
        assertThat(call("PUT", URL + "/" + first, bad).status()).isEqualTo(400);

        AuthApi.Response unset = call("PUT", URL + "/" + second, body("province", "NewP", "isDefault", false));
        assertThat(unset.data().path("isDefault").asBoolean()).isFalse();
        assertThat(unset.data().path("province").asString()).isEqualTo("NewP");
        assertThat(call("PUT", URL + "/" + second, body("phone", "123")).status())
                .isEqualTo(422);
        assertThat(call("PUT", URL + "/" + second, body()).status()).isEqualTo(200);

        AuthApi.Response setDefault = call("PUT", URL + "/" + first + "/default", null);
        assertThat(setDefault.data().path("isDefault").asBoolean()).isTrue();
        assertThat(call("PUT", URL + "/999999/default", null).status()).isEqualTo(404);

        assertThat(call("DELETE", URL + "/" + first, null).status()).isEqualTo(200);
        assertThat(call("GET", URL + "/default", null).data().path("id").asInt())
                .isEqualTo(second);
        assertThat(call("GET", URL + "/" + first, null).status()).isEqualTo(404);
        assertThat(call("DELETE", URL + "/" + first, null).status()).isEqualTo(404);
        assertThat(call("PUT", URL + "/" + first, body("name", "x")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("账号清理删除该用户全部地址")
    void accountCleaner() throws Exception {
        create(named("gone"));
        AccountDataCleaner cleaner = cleaners.stream()
                .filter(c -> c.name().equals("addresses"))
                .findFirst()
                .orElseThrow();
        assertThat(cleaner.order()).isEqualTo(110);
        cleaner.clean(user.id());
        assertThat(jdbc.queryForObject("select count(*) from address where \"userId\" = ?", Integer.class, user.id()))
                .isZero();
    }

    static List<Integer> ids(JsonNode list) {
        List<Integer> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.path("id").asInt()));
        return ids;
    }
}
