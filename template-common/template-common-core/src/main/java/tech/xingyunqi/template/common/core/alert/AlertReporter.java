package tech.xingyunqi.template.common.core.alert;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** 运营告警上报端口：同一 {@code dedupKey} 已有未确认告警时不重复记录，写入失败只记日志、不抛错。 */
public interface AlertReporter {

    /** 记录了新告警时返回其 id，被去重或写入失败时返回 null。 */
    @Nullable
    Integer raise(
            String type, AlertLevel level, String message, @Nullable Map<String, ?> payload, @Nullable String dedupKey);

    default @Nullable Integer raise(String type, AlertLevel level, String message) {
        return raise(type, level, message, null, null);
    }
}
