package tech.xingyunqi.template.common.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Redis 缓存，键与值格式与 nestjs Keyv 相同，两个后端共用 Redis 时互认。 */
public final class RedisCache implements Cache {

    static final String NAMESPACE = "cache";

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final Clock clock;

    public RedisCache(StringRedisTemplate redis, JsonMapper mapper, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
    }

    static String key(String key) {
        return NAMESPACE + "::" + key;
    }

    @Override
    public @Nullable JsonNode get(String key) {
        String raw = redis.opsForValue().get(key(key));
        if (raw == null) {
            return null;
        }
        JsonNode payload = mapper.readTree(raw);
        JsonNode expires = payload.get("expires");
        if (expires != null && expires.isNumber() && expires.asLong() <= clock.millis()) {
            return null;
        }
        JsonNode value = payload.get("value");
        return value == null || value.isNull() ? null : value;
    }

    @Override
    public void set(String key, Object value, long ttlMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("value", value);
        payload.put("expires", clock.millis() + ttlMs);
        redis.opsForValue().set(key(key), mapper.writeValueAsString(payload), Duration.ofMillis(ttlMs));
    }

    @Override
    public void delete(String key) {
        redis.delete(key(key));
    }
}
