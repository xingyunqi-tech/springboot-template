package tech.xingyunqi.template.infrastructure.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HeapUsageTest {

    @Test
    @DisplayName("取结束时间最晚的一次 GC 后的堆占用")
    void latestCollectionWins() {
        assertThat(HeapUsage.latest(
                        List.of(
                                new HeapUsage.Collection(100, 300_000_000),
                                new HeapUsage.Collection(250, 120_000_000),
                                new HeapUsage.Collection(200, 400_000_000)),
                        900_000_000))
                .isEqualTo(120_000_000);
    }

    @Test
    @DisplayName("堆占用为 0 的记录（停顿阶段）不参与比较")
    void ignoresCollectionsWithoutHeapUsage() {
        assertThat(HeapUsage.latest(
                        List.of(new HeapUsage.Collection(300, 0), new HeapUsage.Collection(300, 150_000_000)),
                        900_000_000))
                .isEqualTo(150_000_000);
    }

    @Test
    @DisplayName("尚无 GC 记录时取当前已用量")
    void fallsBackToCurrentUsage() {
        assertThat(HeapUsage.latest(List.of(), 42_000_000)).isEqualTo(42_000_000);
    }

    @Test
    @DisplayName("GC 后的堆占用包含老年代中存活的对象")
    void includesRetainedOldGenerationObjects() {
        List<byte[]> retained = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            retained.add(new byte[1 << 20]);
        }
        System.gc();
        long checksum = 0;
        for (int i = 0; i < 4_000; i++) {
            byte[] garbage = new byte[64 * 1024];
            garbage[i % garbage.length] = 1;
            checksum += garbage[i % garbage.length];
        }
        assertThat(checksum).isEqualTo(4_000);
        assertThat(HeapUsage.afterGc()).isGreaterThanOrEqualTo(64L << 20);
        assertThat(retained).hasSize(64);
    }
}
