package tech.xingyunqi.template.common.redis;

import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tools.jackson.databind.JsonNode;

/** 每次读取计入 {@code cache_hits_total} / {@code cache_misses_total}（{@code cache_name="cache"}）。 */
public final class MeteredCache implements Cache {

    static final String CACHE_NAME = RedisCache.NAMESPACE;

    private final Cache delegate;
    private final AppMetrics metrics;

    public MeteredCache(Cache delegate, AppMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public @Nullable JsonNode get(String key) {
        JsonNode value = delegate.get(key);
        if (value == null) {
            metrics.cacheMiss(CACHE_NAME);
        } else {
            metrics.cacheHit(CACHE_NAME);
        }
        return value;
    }

    @Override
    public void set(String key, Object value, long ttlMs) {
        delegate.set(key, value, ttlMs);
    }

    @Override
    public void delete(String key) {
        delegate.delete(key);
    }
}
