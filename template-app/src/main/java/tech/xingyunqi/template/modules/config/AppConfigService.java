package tech.xingyunqi.template.modules.config;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.json.JsCompatibleModule;
import tech.xingyunqi.template.common.mybatis.AfterCommit;
import tech.xingyunqi.template.common.mybatis.WriteTransaction;
import tech.xingyunqi.template.common.redis.Cache;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 远程配置（nestjs AppConfigService）：缓存键带写入版本号，写入提交后改读新版本号对应的缓存。 */
@Service
public class AppConfigService {

    static final long CONFIG_CACHE_TTL_MS = 60_000;
    static final long CONFIG_VERSION_TTL_MS = 24 * 60 * 60 * 1000;

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigService.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ConfigRepository repository;
    private final Cache cache;

    public AppConfigService(ConfigRepository repository, Cache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    static String versionKey(String key) {
        return "config:version:" + key;
    }

    static String cacheKey(String key, String version) {
        return "config:" + key + ":" + version;
    }

    public ConfigEntry findPublicByKey(String key) {
        ConfigEntry config = findEffective(key);
        if (config == null || !config.isPublic()) {
            throw notFound();
        }
        return config;
    }

    public ConfigEntry findByKey(String key) {
        ConfigEntry config = findEffective(key);
        if (config == null) {
            throw notFound();
        }
        return config;
    }

    /** 登记项的配置值（{@code value.value}），未配置时为登记的默认值；两者都没有时为 null。 */
    public @Nullable JsonNode getValue(String key) {
        ConfigEntry config = load(key, true);
        if (config != null && config.value().isObject() && config.value().has("value")) {
            return config.value().get("value");
        }
        return ConfigDefinitions.find(key)
                .map(ConfigDefinitions.Definition::defaultValue)
                .orElse(null);
    }

    /** 通用写接口入口，拒绝由专用接口管理的登记项。 */
    @WriteTransaction
    public ConfigEntry upsertGeneric(String key, JsonNode value, @Nullable Boolean isPublic) {
        if (ConfigDefinitions.find(key)
                .map(ConfigDefinitions.Definition::dedicated)
                .orElse(false)) {
            throw HttpStatusException.unprocessable(
                    Map.of("errors", Map.of("key", "configManagedByDedicatedEndpoint")));
        }
        return upsert(key, value, isPublic);
    }

    /** 登记项的 isPublic 以登记表为准。 */
    @WriteTransaction
    public ConfigEntry upsert(String key, JsonNode value, @Nullable Boolean isPublic) {
        LOG.info("Upsert config: {}", key);
        Optional<ConfigDefinitions.Definition> definition = ConfigDefinitions.find(key);
        ConfigEntry config = repository.upsert(
                key,
                value,
                definition.map(ConfigDefinitions.Definition::isPublic).orElse(isPublic));
        AfterCommit.always(() -> cache.set(versionKey(key), UUID.randomUUID().toString(), CONFIG_VERSION_TTL_MS));
        return definition.map(found -> config.withPublic(found.isPublic())).orElse(config);
    }

    private @Nullable ConfigEntry findEffective(String key) {
        Optional<ConfigDefinitions.Definition> definition = ConfigDefinitions.find(key);
        ConfigEntry stored = load(key, definition.isPresent());
        if (stored != null) {
            return definition.map(found -> stored.withPublic(found.isPublic())).orElse(stored);
        }
        JsonNode defaultValue =
                definition.map(ConfigDefinitions.Definition::defaultValue).orElse(null);
        if (defaultValue == null) {
            return null;
        }
        ObjectNode value = NODES.objectNode();
        value.set("value", defaultValue);
        return new ConfigEntry(key, value, definition.get().isPublic(), null, null);
    }

    private @Nullable ConfigEntry load(String key, boolean cacheMissing) {
        JsonNode version = cache.get(versionKey(key));
        String cacheKey = cacheKey(key, version == null ? "0" : version.asString());
        JsonNode cached = cache.get(cacheKey);
        if (cached != null) {
            JsonNode config = cached.get("config");
            return config == null || config.isNull() ? null : fromCache(config);
        }
        ConfigEntry config = repository.findByKey(key).orElse(null);
        if (config != null || cacheMissing) {
            ObjectNode entry = NODES.objectNode();
            entry.set("config", config == null ? NODES.nullNode() : toCache(config));
            cache.set(cacheKey, entry, CONFIG_CACHE_TTL_MS);
        }
        return config;
    }

    private static ObjectNode toCache(ConfigEntry config) {
        ObjectNode node = NODES.objectNode();
        node.put("key", config.key());
        node.set("value", config.value());
        node.put("isPublic", config.isPublic());
        node.put("createdAt", timestamp(config.createdAt()));
        node.put("updatedAt", timestamp(config.updatedAt()));
        return node;
    }

    private static ConfigEntry fromCache(JsonNode node) {
        return new ConfigEntry(
                node.path("key").asString(),
                node.path("value"),
                node.path("isPublic").asBoolean(),
                instant(node.get("createdAt")),
                instant(node.get("updatedAt")));
    }

    private static @Nullable String timestamp(@Nullable Instant value) {
        return value == null ? null : JsCompatibleModule.isoTimestamp(value);
    }

    private static @Nullable Instant instant(@Nullable JsonNode value) {
        return value == null || !value.isString() ? null : Instant.parse(value.asString());
    }

    private static HttpStatusException notFound() {
        return HttpStatusException.notFound(Map.of("error", "configNotFound"));
    }
}
