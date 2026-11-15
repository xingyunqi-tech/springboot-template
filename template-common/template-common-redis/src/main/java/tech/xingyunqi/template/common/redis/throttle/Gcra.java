package tech.xingyunqi.template.common.redis.throttle;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** GCRA 判定，时间均为整数微秒；TAT 在 Redis 中存为最多 3 位小数的十进制毫秒串。 */
public final class Gcra {

    public static final long MICROS_PER_MILLI = 1_000L;
    public static final long MICROS_PER_SECOND = 1_000_000L;

    private Gcra() {}

    /** 一次请求的判定；{@code resetMicros} 为放行后 TAT 距当前的时长，{@code waitMicros} 为拒绝时需等待的时长。 */
    public record Decision(boolean allowed, long remaining, long resetMicros, long waitMicros) {

        /** {@code X-RateLimit-Reset}。 */
        public long resetSeconds() {
            return ceilSeconds(resetMicros);
        }

        /** {@code Retry-After}，至少 1 秒。 */
        public long retryAfterSeconds() {
            return Math.max(1, ceilSeconds(waitMicros));
        }

        private static long ceilSeconds(long micros) {
            return micros <= 0 ? 0 : Math.ceilDiv(micros, MICROS_PER_SECOND);
        }
    }

    /** 判定结果与放行后应写回的 TAT（拒绝时为原 TAT）。 */
    public record Outcome(Decision decision, long tat) {}

    public static long interval(long periodMicros, int limit) {
        return periodMicros / limit;
    }

    /** {@code storedTat} 为 null 表示新键。 */
    public static Outcome decide(@Nullable Long storedTat, long now, long periodMicros, long intervalMicros) {
        long tat = storedTat == null ? now : Math.max(storedTat, now);
        long wait = (tat - now) + intervalMicros - periodMicros;
        if (wait > 0) {
            return new Outcome(new Decision(false, 0, tat - now, wait), tat);
        }
        long next = tat + intervalMicros;
        long remaining = (periodMicros - (next - now)) / intervalMicros;
        return new Outcome(new Decision(true, remaining, next - now, 0), next);
    }

    public static String formatTat(long micros) {
        return BigDecimal.valueOf(micros, 3).toPlainString();
    }

    /** 十进制毫秒串四舍五入到整数微秒，与脚本中的 {@code math.floor(tonumber(v) * 1000 + 0.5)} 相同。 */
    public static long parseTat(String millis) {
        return (long) Math.floor(Double.parseDouble(millis) * MICROS_PER_MILLI + 0.5);
    }
}
