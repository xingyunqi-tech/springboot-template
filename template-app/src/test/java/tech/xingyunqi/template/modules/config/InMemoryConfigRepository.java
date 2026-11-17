package tech.xingyunqi.template.modules.config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** 内存配置仓储，记录 findByKey 次数；写入的时间固定为 {@link #NOW}。 */
public final class InMemoryConfigRepository implements ConfigRepository {

    public static final Instant NOW = Instant.parse("2026-11-16T08:00:00.123Z");

    public final Map<String, ConfigEntry> rows = new HashMap<>();
    public final AtomicInteger reads = new AtomicInteger();

    @Override
    public Optional<ConfigEntry> findByKey(String key) {
        reads.incrementAndGet();
        return Optional.ofNullable(rows.get(key));
    }

    @Override
    public ConfigEntry upsert(String key, JsonNode value, @Nullable Boolean isPublic) {
        ConfigEntry existing = rows.get(key);
        boolean visible = isPublic != null ? isPublic : existing != null && existing.isPublic();
        ConfigEntry entry = new ConfigEntry(key, value, visible, NOW, NOW);
        rows.put(key, entry);
        return entry;
    }
}
