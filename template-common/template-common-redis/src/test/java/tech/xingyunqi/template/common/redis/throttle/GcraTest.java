package tech.xingyunqi.template.common.redis.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GcraTest {

    static final long MINUTE = 60_000_000L;
    /** 268382706.34399077 ms 换算为整数微秒。 */
    static final long FIXED_NOW = 268_382_706_344L;

    @Test
    @DisplayName("limit=1 的全新键第一个请求放行，第二个请求拒绝并等待整个周期")
    void limitOneFreshKeyAllowsFirstRequest() {
        long interval = Gcra.interval(MINUTE, 1);
        Gcra.Outcome first = Gcra.decide(null, FIXED_NOW, MINUTE, interval);
        assertThat(first.decision()).isEqualTo(new Gcra.Decision(true, 0, MINUTE, 0));
        assertThat(first.decision().resetSeconds()).isEqualTo(60);
        assertThat(first.tat()).isEqualTo(FIXED_NOW + MINUTE);

        Gcra.Outcome second = Gcra.decide(first.tat(), FIXED_NOW, MINUTE, interval);
        assertThat(second.decision().allowed()).isFalse();
        assertThat(second.decision().retryAfterSeconds()).isEqualTo(60);
        assertThat(second.tat()).isEqualTo(first.tat());
    }

    @Test
    @DisplayName("固定 now=268382706.34399077ms、period=60000ms、limit=1：首个请求放行")
    void fixedNowCase() {
        assertThat(Gcra.parseTat("268382706.34399077")).isEqualTo(FIXED_NOW);
        Gcra.Outcome outcome = Gcra.decide(null, FIXED_NOW, MINUTE, Gcra.interval(MINUTE, 1));
        assertThat(outcome.decision().allowed()).isTrue();
        assertThat(Gcra.formatTat(outcome.tat())).isEqualTo("268442706.344");
    }

    @Test
    @DisplayName("读取 fastapi 写入的多位小数 TAT 后判定正确，写回 3 位小数毫秒串")
    void readsFastapiTat() {
        long stored = Gcra.parseTat("268382706.34399077");
        long interval = Gcra.interval(MINUTE, 2);

        Gcra.Outcome early = Gcra.decide(stored, FIXED_NOW - 30_000_001L, MINUTE, interval);
        assertThat(early.decision().allowed()).isFalse();
        assertThat(early.decision().waitMicros()).isEqualTo(1);
        assertThat(early.decision().retryAfterSeconds()).isEqualTo(1);

        Gcra.Outcome onTime = Gcra.decide(stored, FIXED_NOW - 30_000_000L, MINUTE, interval);
        assertThat(onTime.decision()).isEqualTo(new Gcra.Decision(true, 0, MINUTE, 0));
        assertThat(Gcra.formatTat(onTime.tat())).isEqualTo("268412706.344");
    }

    @Test
    @DisplayName("interval 向下取整到微秒，limit 次请求不超过 period")
    void intervalFloorsToMicros() {
        long interval = Gcra.interval(MINUTE, 7);
        assertThat(interval).isEqualTo(8_571_428L);
        Long tat = null;
        for (int i = 0; i < 7; i++) {
            Gcra.Outcome outcome = Gcra.decide(tat, 0, MINUTE, interval);
            assertThat(outcome.decision().allowed()).isTrue();
            assertThat(outcome.decision().remaining()).isEqualTo(6 - i);
            tat = outcome.tat();
        }
        assertThat(Gcra.decide(tat, 0, MINUTE, interval).decision().allowed()).isFalse();
    }

    @Test
    @DisplayName("配额按 interval 逐步恢复，Retry-After 与 Reset 向上取整到秒")
    void replenishesGradually() {
        long interval = Gcra.interval(MINUTE, 3);
        Long tat = null;
        for (int i = 0; i < 3; i++) {
            tat = Gcra.decide(tat, 0, MINUTE, interval).tat();
        }
        Gcra.Decision blocked = Gcra.decide(tat, 0, MINUTE, interval).decision();
        assertThat(blocked.waitMicros()).isEqualTo(20_000_000L);
        assertThat(blocked.retryAfterSeconds()).isEqualTo(20);

        assertThat(Gcra.decide(tat, 19_999_999L, MINUTE, interval).decision().allowed())
                .isFalse();
        Gcra.Decision later = Gcra.decide(tat, 20_000_000L, MINUTE, interval).decision();
        assertThat(later.allowed()).isTrue();
        assertThat(later.remaining()).isZero();
        assertThat(new Gcra.Decision(true, 0, 1, 0).resetSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("TAT 写为 3 位小数毫秒串，读回时四舍五入到整数微秒")
    void tatFormat() {
        assertThat(Gcra.formatTat(1_000L)).isEqualTo("1.000");
        assertThat(Gcra.formatTat(268_382_706_344L)).isEqualTo("268382706.344");
        assertThat(Gcra.parseTat("1.0006")).isEqualTo(1_001L);
        assertThat(Gcra.parseTat("1.0004")).isEqualTo(1_000L);
        assertThat(Gcra.parseTat("1735689600123.456")).isEqualTo(1_735_689_600_123_456L);
    }
}
