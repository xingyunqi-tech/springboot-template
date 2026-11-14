package tech.xingyunqi.template.common.redis;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 进程内缓存（{@code REDIS_ENABLED=false}，仅单实例开发 / 测试），最多保留 {@value #MAX_ITEMS} 项。 */
public final class MemoryCache implements Cache {

    static final int MAX_ITEMS = 10_000;

    private record Entry(JsonNode value, long expiresAt) {}

    private final JsonMapper mapper;
    private final Clock clock;
    private final Map<String, Entry> items = new LinkedHashMap<>();

    public MemoryCache(JsonMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public synchronized @Nullable JsonNode get(String key) {
        Entry entry = items.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt() <= clock.millis()) {
            items.remove(key);
            return null;
        }
        return entry.value();
    }

    @Override
    public synchronized void set(String key, Object value, long ttlMs) {
        long now = clock.millis();
        items.remove(key);
        if (items.size() >= MAX_ITEMS) {
            items.values().removeIf(entry -> entry.expiresAt() <= now);
            Iterator<String> oldest = items.keySet().iterator();
            while (items.size() >= MAX_ITEMS && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        items.put(key, new Entry(mapper.valueToTree(value), now + ttlMs));
    }

    @Override
    public synchronized void delete(String key) {
        items.remove(key);
    }
}
