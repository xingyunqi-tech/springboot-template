package tech.xingyunqi.template.common.redis.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemoryGcraTest {

    static final long MINUTE = 60_000_000L;

    @Test
    @DisplayName("固定 now=268382706.34399077ms、period=60000ms、limit=1：首个请求放行，第二个拒绝")
    void fixedNowCase() {
        MemoryGcra memory = new MemoryGcra();
        long interval = Gcra.interval(MINUTE, 1);
        assertThat(memory.consume("k", MINUTE, interval, GcraTest.FIXED_NOW).allowed())
                .isTrue();
        assertThat(memory.consume("k", MINUTE, interval, GcraTest.FIXED_NOW).allowed())
                .isFalse();
    }

    @Test
    @DisplayName("各键独立计数，拒绝不推进 TAT")
    void keysAreIndependent() {
        MemoryGcra memory = new MemoryGcra();
        long interval = Gcra.interval(MINUTE, 1);
        assertThat(memory.consume("a", MINUTE, interval, 0).allowed()).isTrue();
        assertThat(memory.consume("a", MINUTE, interval, 0).allowed()).isFalse();
        assertThat(memory.consume("b", MINUTE, interval, 0).allowed()).isTrue();
        assertThat(memory.consume("a", MINUTE, interval, MINUTE).allowed()).isTrue();
    }

    @Test
    @DisplayName("键满时先清除已过期的键，仍满则淘汰 TAT 最小的键")
    void evictsWhenFull() {
        MemoryGcra memory = new MemoryGcra();
        long interval = Gcra.interval(MINUTE, 1);
        for (int i = 0; i < MemoryGcra.MAX_KEYS; i++) {
            memory.consume("k" + i, MINUTE, interval, i);
        }
        assertThat(memory.size()).isEqualTo(MemoryGcra.MAX_KEYS);

        memory.consume("new", MINUTE, interval, MemoryGcra.MAX_KEYS);
        assertThat(memory.size()).isEqualTo(MemoryGcra.MAX_KEYS);
        assertThat(memory.consume("k0", MINUTE, interval, MemoryGcra.MAX_KEYS).allowed())
                .isTrue();
        assertThat(memory.consume("k5", MINUTE, interval, MemoryGcra.MAX_KEYS).allowed())
                .isFalse();

        memory.consume("late", MINUTE, interval, MINUTE + MemoryGcra.MAX_KEYS);
        assertThat(memory.size()).isLessThan(MemoryGcra.MAX_KEYS);
    }
}
