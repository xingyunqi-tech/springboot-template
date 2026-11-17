package tech.xingyunqi.template.modules.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.json.JsCompatibleModule;
import tech.xingyunqi.template.common.redis.MemoryCache;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AppConfigServiceTest {

    static final JsonMapper JSON =
            JsonMapper.builder().addModule(new JsCompatibleModule()).build();
    static final Instant NOW = InMemoryConfigRepository.NOW;

    final InMemoryConfigRepository repository = new InMemoryConfigRepository();
    final AppConfigService service = new AppConfigService(repository, new MemoryCache(JSON, Clock.systemUTC()));

    static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    @DisplayName("公开读取：非公开或不存在的配置 404 configNotFound，登记项未写入时返回默认值且时间为 null")
    void publicRead() {
        assertThat(service.findPublicByKey("ai.enabled").value()).isEqualTo(json("{\"value\":true}"));
        assertThat(service.findPublicByKey("ai.enabled").createdAt()).isNull();
        for (String key : new String[] {"ai.ratePerMinute", "unknown.key", "ai.model"}) {
            assertThatThrownBy(() -> service.findPublicByKey(key))
                    .isInstanceOfSatisfying(HttpStatusException.class, e -> {
                        assertThat(e.status()).isEqualTo(404);
                        assertThat(e.response()).isEqualTo(Map.of("error", "configNotFound"));
                    });
        }
        assertThat(service.findByKey("ai.ratePerMinute").isPublic()).isFalse();
        assertThatThrownBy(() -> service.findByKey("ai.model")).isInstanceOf(HttpStatusException.class);
    }

    @Test
    @DisplayName("登记项的 isPublic 以登记表为准，未登记项按存储值")
    void registeredVisibility() {
        repository.rows.put(
                "ai.ratePerMinute", new ConfigEntry("ai.ratePerMinute", json("{\"value\":3}"), true, NOW, NOW));
        repository.rows.put("custom", new ConfigEntry("custom", json("{}"), true, NOW, NOW));

        assertThatThrownBy(() -> service.findPublicByKey("ai.ratePerMinute")).isInstanceOf(HttpStatusException.class);
        assertThat(service.findPublicByKey("custom").createdAt()).isEqualTo(NOW);
        assertThat(service.upsert("ai.ratePerMinute", json("{\"value\":4}"), true)
                        .isPublic())
                .isFalse();
        assertThat(repository.rows.get("ai.ratePerMinute").isPublic()).isFalse();
    }

    @Test
    @DisplayName("读取结果缓存；写入后换新版本号，随后读取到新值")
    void cacheVersioning() {
        repository.rows.put("custom", new ConfigEntry("custom", json("{\"a\":1}"), true, NOW, NOW));
        service.findPublicByKey("custom");
        ConfigEntry cached = service.findPublicByKey("custom");
        assertThat(repository.reads).hasValue(1);
        assertThat(cached).isEqualTo(new ConfigEntry("custom", json("{\"a\":1}"), true, NOW, NOW));

        service.upsertGeneric("custom", json("{\"a\":2}"), null);
        ConfigEntry reloaded = service.findPublicByKey("custom");
        assertThat(reloaded.value()).isEqualTo(json("{\"a\":2}"));
        assertThat(reloaded.createdAt()).isEqualTo(NOW);
        assertThat(repository.reads).hasValue(2);
    }

    @Test
    @DisplayName("登记项不存在时也缓存空结果，未登记项不缓存")
    void cacheMissing() {
        service.getValue("ai.model");
        service.getValue("ai.model");
        assertThat(repository.reads).hasValue(1);

        assertThatThrownBy(() -> service.findByKey("unknown")).isInstanceOf(HttpStatusException.class);
        assertThatThrownBy(() -> service.findByKey("unknown")).isInstanceOf(HttpStatusException.class);
        assertThat(repository.reads).hasValue(3);
    }

    @Test
    @DisplayName("getValue 取 value.value，缺少时取登记默认值，都没有时为 null")
    void getValue() {
        assertThat(service.getValue("ai.quota.chat")).isEqualTo(json("20"));
        assertThat(service.getValue("ai.model")).isNull();
        repository.rows.put("ai.model", new ConfigEntry("ai.model", json("{\"value\":null}"), false, NOW, NOW));
        repository.rows.put("ai.quota.chat", new ConfigEntry("ai.quota.chat", json("{\"other\":1}"), true, NOW, NOW));
        AppConfigService fresh = new AppConfigService(repository, new MemoryCache(JSON, Clock.systemUTC()));
        assertThat(fresh.getValue("ai.model")).isEqualTo(json("null"));
        assertThat(fresh.getValue("ai.quota.chat")).isEqualTo(json("20"));
    }

    @Test
    @DisplayName("通用写接口拒绝专用登记项：422 configManagedByDedicatedEndpoint")
    void dedicatedKey() {
        assertThatThrownBy(() -> service.upsertGeneric("app.profile", json("{}"), null))
                .isInstanceOfSatisfying(HttpStatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.response())
                            .isEqualTo(Map.of("errors", Map.of("key", "configManagedByDedicatedEndpoint")));
                });
        assertThat(repository.rows).isEmpty();
    }
}
