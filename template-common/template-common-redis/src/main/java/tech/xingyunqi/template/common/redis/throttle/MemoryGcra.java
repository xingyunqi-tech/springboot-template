package tech.xingyunqi.template.common.redis.throttle;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 进程内 GCRA，最多保留 {@value #MAX_KEYS} 个键。 */
public final class MemoryGcra {

    static final int MAX_KEYS = 10_000;

    private final Map<String, Long> tats = new HashMap<>();

    public synchronized Gcra.Decision consume(String key, long periodMicros, long intervalMicros, long now) {
        Gcra.Outcome outcome = Gcra.decide(tats.get(key), now, periodMicros, intervalMicros);
        if (!outcome.decision().allowed()) {
            return outcome.decision();
        }
        if (!tats.containsKey(key) && tats.size() >= MAX_KEYS) {
            evict(now);
        }
        tats.put(key, outcome.tat());
        return outcome.decision();
    }

    synchronized int size() {
        return tats.size();
    }

    synchronized void clear() {
        tats.clear();
    }

    /** 先清掉已过期的键，仍满时淘汰 TAT 最小的键。 */
    private void evict(long now) {
        tats.values().removeIf(tat -> tat <= now);
        while (tats.size() >= MAX_KEYS) {
            Iterator<Map.Entry<String, Long>> entries = tats.entrySet().iterator();
            Map.Entry<String, Long> oldest = entries.next();
            while (entries.hasNext()) {
                Map.Entry<String, Long> entry = entries.next();
                if (entry.getValue() < oldest.getValue()) {
                    oldest = entry;
                }
            }
            tats.remove(oldest.getKey());
        }
    }
}
