package tech.xingyunqi.template.infrastructure.accountcleanup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.xingyunqi.template.common.core.alert.AlertLevel;
import tech.xingyunqi.template.common.core.alert.AlertReporter;

/** 清理最终失败或入队失败时的告警（nestjs {@code raiseAccountCleanupFailure}），告警失败只记日志。 */
final class AccountCleanupAlerts {

    static final String TYPE = "accountCleanupFailed";

    private static final Logger LOG = LoggerFactory.getLogger(AccountCleanupAlerts.class);

    private AccountCleanupAlerts() {}

    static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.toString() : message;
    }

    static void raise(AlertReporter alerts, int userId, Throwable error, @Nullable List<String> failedCleaners) {
        String reason = messageOf(error);
        LOG.error("Account cleanup for user {} failed: {}", userId, reason);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("reason", reason);
        if (failedCleaners != null) {
            payload.put("failedCleaners", failedCleaners);
        }
        try {
            alerts.raise(TYPE, AlertLevel.WARN, "用户 " + userId + " 的注销数据清理失败：" + reason, payload, TYPE + ":" + userId);
        } catch (RuntimeException e) {
            LOG.error("Failed to raise alert for account cleanup of user {}: {}", userId, messageOf(e));
        }
    }
}
