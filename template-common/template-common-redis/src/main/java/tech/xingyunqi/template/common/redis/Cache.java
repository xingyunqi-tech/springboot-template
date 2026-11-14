package tech.xingyunqi.template.common.redis;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** 与 nestjs cache-manager + Keyv 兼容的缓存：Redis 键 {@code cache::<key>}，值 {@code {"value", "expires"}}。 */
public interface Cache {

    long DEFAULT_TTL_MS = 60_000;

    @Nullable
    JsonNode get(String key);

    void set(String key, Object value, long ttlMs);

    default void set(String key, Object value) {
        set(key, value, DEFAULT_TTL_MS);
    }

    void delete(String key);
}
