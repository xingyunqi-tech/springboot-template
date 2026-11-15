package tech.xingyunqi.template.infrastructure.alerting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.core.alert.AlertLevel;
import tech.xingyunqi.template.common.core.alert.AlertReporter;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.core.permissions.RoleIds;
import tools.jackson.databind.json.JsonMapper;

/**
 * nestjs {@code AlertService.raise}：写 {@code admin_alert}（同一 dedupKey 有未确认告警时去重），计数，并给前 {@value #RECIPIENT_LIMIT}
 * 个管理员写站内通知（{@code [<level>] <type>}）；任一步失败只记日志。
 */
@Service
public class AdminAlertService implements AlertReporter {

    static final int RECIPIENT_LIMIT = 50;
    static final int TITLE_MAX_LENGTH = 200;
    static final String CATEGORY = "SYSTEM";
    static final String IN_APP = "IN_APP";

    private static final Logger LOG = LoggerFactory.getLogger(AdminAlertService.class);

    private final AlertMapper mapper;
    private final AppMetrics metrics;
    private final JsonMapper json;

    public AdminAlertService(AlertMapper mapper, AppMetrics metrics, JsonMapper json) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.json = json;
    }

    @Override
    public @Nullable Integer raise(
            String type,
            AlertLevel level,
            String message,
            @Nullable Map<String, ?> payload,
            @Nullable String dedupKey) {
        String key = dedupKey == null ? type : dedupKey;
        Integer id;
        try {
            id = mapper.insertIfNoOpen(
                    type, level.name(), message, payload == null ? null : json.writeValueAsString(payload), key);
        } catch (RuntimeException e) {
            LOG.error("Failed to record alert {}: {}", key, e.getMessage());
            return null;
        }
        if (id == null) {
            metrics.adminAlertDeduplicated(type);
            return null;
        }
        metrics.adminAlert(type, level.name());
        LOG.warn("Alert raised [{}] {}: {}", level, type, message);
        notifyAdmins(id, type, level, message, key);
        return id;
    }

    private void notifyAdmins(int alertId, String type, AlertLevel level, String message, String dedupKey) {
        try {
            String title = "[" + level.name() + "] " + type;
            if (title.length() > TITLE_MAX_LENGTH) {
                title = title.substring(0, TITLE_MAX_LENGTH);
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("alertId", alertId);
            metadata.put("dedupKey", dedupKey);
            String metadataJson = json.writeValueAsString(metadata);
            for (int adminId : mapper.findUserIdsByRole(RoleIds.ADMIN, RECIPIENT_LIMIT)) {
                mapper.insertNotification(
                        adminId,
                        notificationType(level),
                        CATEGORY,
                        title,
                        message,
                        metadataJson,
                        json.writeValueAsString(channels(mapper.findSetting(adminId, CATEGORY))));
            }
        } catch (RuntimeException e) {
            LOG.error("Failed to notify admins of alert {}: {}", alertId, e.getMessage());
        }
    }

    static String notificationType(AlertLevel level) {
        return switch (level) {
            case INFO -> "INFO";
            case WARN -> "WARNING";
            case CRITICAL -> "ERROR";
        };
    }

    /** 用户关闭了站内信时渠道为空数组（nestjs 按通知设置过滤渠道）。 */
    static List<String> channels(AlertMapper.@Nullable ChannelSetting setting) {
        List<String> channels = new ArrayList<>();
        if (setting == null || setting.inApp()) {
            channels.add(IN_APP);
        }
        return channels;
    }
}
