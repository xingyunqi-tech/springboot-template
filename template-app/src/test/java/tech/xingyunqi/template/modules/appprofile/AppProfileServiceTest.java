package tech.xingyunqi.template.modules.appprofile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.json.JsCompatibleModule;
import tech.xingyunqi.template.common.json.JsStrings;
import tech.xingyunqi.template.common.redis.MemoryCache;
import tech.xingyunqi.template.modules.config.AppConfigService;
import tech.xingyunqi.template.modules.config.InMemoryConfigRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AppProfileServiceTest {

    static final JsonMapper JSON =
            JsonMapper.builder().addModule(new JsCompatibleModule()).build();

    final InMemoryConfigRepository repository = new InMemoryConfigRepository();
    final AppProfileService service =
            new AppProfileService(new AppConfigService(repository, new MemoryCache(JSON, Clock.systemUTC())));

    @Test
    @DisplayName("未配置时每个字段为 null")
    void emptyProfile() {
        assertThat(service.get())
                .containsOnlyKeys(AppProfileService.FIELDS)
                .allSatisfy((field, value) -> assertThat(value).isNull());
    }

    @Test
    @DisplayName("整体覆盖：字符串去除首尾空白，空白、非字符串与未传字段保存为 null，未知字段丢弃")
    void update() {
        JsonNode body = JSON.readTree(
                "{\"appName\":\"\\u00a0 备考 \\u3000\",\"slogan\":\"  \",\"companyName\":1,\"servicePhone\":\"400\","
                        + "\"unknown\":\"x\"}");

        Map<String, ?> profile = service.update(body);

        assertThat(profile.get("appName")).isEqualTo("备考");
        assertThat(profile.get("slogan")).isNull();
        assertThat(profile.get("companyName")).isNull();
        assertThat(profile.get("servicePhone")).isEqualTo("400");
        assertThat(profile).doesNotContainKey("unknown");
        JsonNode stored = repository.rows.get("app.profile").value();
        assertThat(stored.path("value").propertyNames()).containsExactlyElementsOf(AppProfileService.FIELDS);
        assertThat(repository.rows.get("app.profile").isPublic()).isFalse();
        assertThat(service.get()).isEqualTo(profile);
    }

    @Test
    @DisplayName("JavaScript trim 去除 Unicode 空白")
    void jsTrim() {
        assertThat(JsStrings.trim("\u2003\ufeff a \u00a0\n\u2028")).isEqualTo("a");
        assertThat(JsStrings.trim("   ")).isEmpty();
        assertThat(JsStrings.trim("\u0001a\u0085")).isEqualTo("\u0001a\u0085");
    }

    @Test
    @DisplayName("请求体不是对象时所有字段为 null")
    void nonObject() {
        assertThat(service.update(null).values()).containsOnlyNulls();
        assertThat(AppProfileService.profile(JSON.readTree("[1]")).values()).containsOnlyNulls();
    }
}
