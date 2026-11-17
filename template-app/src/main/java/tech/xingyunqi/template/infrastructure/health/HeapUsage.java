package tech.xingyunqi.template.infrastructure.health;

import com.sun.management.GcInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 堆占用：最近一次 GC 结束时全部堆内存池的已用量之和；尚无 GC 记录时取当前已用量。 */
final class HeapUsage {

    record Collection(long endTime, long heapUsedAfter) {}

    private HeapUsage() {}

    static long afterGc() {
        return latest(collections(), currentUsed());
    }

    static long latest(List<Collection> collections, long currentUsed) {
        return collections.stream()
                .filter(c -> c.heapUsedAfter() > 0)
                .max(Comparator.comparingLong(Collection::endTime))
                .map(Collection::heapUsedAfter)
                .orElse(currentUsed);
    }

    static List<Collection> collections() {
        Set<String> heapPools =
                heapPools().stream().map(MemoryPoolMXBean::getName).collect(Collectors.toSet());
        List<Collection> collections = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(gc instanceof com.sun.management.GarbageCollectorMXBean bean)) {
                continue;
            }
            GcInfo info = bean.getLastGcInfo();
            if (info == null) {
                continue;
            }
            long used = 0;
            for (var entry : info.getMemoryUsageAfterGc().entrySet()) {
                if (heapPools.contains(entry.getKey())) {
                    used += entry.getValue().getUsed();
                }
            }
            collections.add(new Collection(info.getEndTime(), used));
        }
        return collections;
    }

    private static long currentUsed() {
        return heapPools().stream()
                .map(MemoryPoolMXBean::getUsage)
                .mapToLong(MemoryUsage::getUsed)
                .sum();
    }

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP && pool.isValid())
                .toList();
    }
}
