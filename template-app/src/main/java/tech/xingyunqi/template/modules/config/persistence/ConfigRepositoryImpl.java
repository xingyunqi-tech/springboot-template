package tech.xingyunqi.template.modules.config.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tech.xingyunqi.template.modules.config.ConfigEntry;
import tech.xingyunqi.template.modules.config.ConfigRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
class ConfigRepositoryImpl implements ConfigRepository {

    private final ConfigMapper mapper;
    private final JsonMapper json;

    ConfigRepositoryImpl(ConfigMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Optional<ConfigEntry> findByKey(String key) {
        ConfigMapper.Row row = mapper.findByKey(key);
        return row == null ? Optional.empty() : Optional.of(entry(row, json.readTree(row.value())));
    }

    @Override
    public ConfigEntry upsert(String key, JsonNode value, @Nullable Boolean isPublic) {
        ConfigMapper.Row existing = mapper.findByKey(key);
        if (existing == null) {
            return entry(mapper.insert(key, json.writeValueAsString(value), Boolean.TRUE.equals(isPublic)), value);
        }
        boolean visible = isPublic == null ? existing.isPublic() : isPublic;
        if (visible == existing.isPublic() && deepEquals(value, json.readTree(existing.value()))) {
            return entry(existing, value);
        }
        return entry(mapper.update(key, json.writeValueAsString(value), visible), value);
    }

    /** TypeORM {@code OrmUtils.deepCompare}：键顺序无关，数字按数值比较。 */
    static boolean deepEquals(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return left.asDouble() == right.asDouble();
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> property : left.properties()) {
                JsonNode other = right.get(property.getKey());
                if (other == null || !deepEquals(property.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            Iterator<JsonNode> others = right.values().iterator();
            for (JsonNode item : left.values()) {
                if (!deepEquals(item, others.next())) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private static ConfigEntry entry(ConfigMapper.Row row, JsonNode value) {
        return new ConfigEntry(row.key(), value, row.isPublic(), utc(row.createdAt()), utc(row.updatedAt()));
    }

    private static Instant utc(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
