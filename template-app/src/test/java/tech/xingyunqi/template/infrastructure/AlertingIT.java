package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.xingyunqi.template.common.core.alert.AlertLevel;
import tech.xingyunqi.template.common.core.alert.AlertReporter;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AlertingIT {

    @Autowired
    AlertReporter alerts;

    @Autowired
    AppMetrics metrics;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("同一 dedupKey 有未确认告警时去重；新告警写 admin_alert、计数并给每个管理员发站内通知")
    void deduplicatesAndNotifiesAdmins() {
        String type = "probe-" + UUID.randomUUID().toString().substring(0, 8);
        Integer first = alerts.raise(type, AlertLevel.WARN, "disk full", Map.of("disk", "/"), type + ":1");
        Integer second = alerts.raise(type, AlertLevel.WARN, "disk full again", null, type + ":1");
        Integer other = alerts.raise(type, AlertLevel.WARN, "other", null, type + ":2");

        assertThat(first).isNotNull();
        assertThat(second).isNull();
        assertThat(other).isNotNull();
        assertThat(metrics.value("admin_alerts_total", type, "WARN")).isEqualTo(2);
        assertThat(metrics.value("admin_alerts_deduplicated_total", type)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "select \"dedupKey\", message, payload::text as payload from admin_alert where type = ? order by id",
                        type))
                .extracting(row -> List.of(
                        String.valueOf(row.get("dedupKey")),
                        String.valueOf(row.get("message")),
                        String.valueOf(row.get("payload"))))
                .containsExactly(
                        List.of(type + ":1", "disk full", "{\"disk\": \"/\"}"), List.of(type + ":2", "other", "null"));

        Integer admins = jdbc.queryForObject(
                "select count(*) from \"user\" where \"roleId\" = 1 and \"deletedAt\" is null", Integer.class);
        List<Map<String, Object>> notifications = jdbc.queryForList(
                "select \"userId\", type, category, content, channels::text as channels, metadata::text as metadata,"
                        + " \"isRead\" from notification where title = ? order by id",
                "[WARN] " + type);
        assertThat(admins).isPositive();
        assertThat(notifications).hasSize(2 * admins);
        Map<String, Object> sample = notifications.getFirst();
        assertThat(sample)
                .containsEntry("type", "WARNING")
                .containsEntry("category", "SYSTEM")
                .containsEntry("content", "disk full")
                .containsEntry("channels", "[\"IN_APP\"]")
                .containsEntry("metadata", "{\"alertId\": " + first + ", \"dedupKey\": \"" + type + ":1\"}")
                .containsEntry("isRead", false);

        jdbc.update("update admin_alert set \"acknowledgedAt\" = now() where \"dedupKey\" = ?", type + ":1");
        Integer again = alerts.raise(type, AlertLevel.CRITICAL, "disk full", null, type + ":1");
        assertThat(again).isNotNull().isNotEqualTo(first);
        assertThat(metrics.value("admin_alerts_total", type, "CRITICAL")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from notification where title = ? and type = 'ERROR'",
                        Integer.class,
                        "[CRITICAL] " + type))
                .isEqualTo(admins);
    }

    @Test
    @DisplayName("dedupKey 缺省为 type；关闭了系统类站内信的管理员收到的通知渠道为空")
    void defaultsAndNotificationSettings() {
        String type = "probe-" + UUID.randomUUID().toString().substring(0, 8);
        Integer admin = jdbc.queryForObject(
                "select min(id) from \"user\" where \"roleId\" = 1 and \"deletedAt\" is null", Integer.class);
        jdbc.update(
                "insert into notification_setting (\"userId\", category, \"enableInApp\") values (?, 'SYSTEM', false)"
                        + " on conflict (\"userId\", category) do update set \"enableInApp\" = false",
                admin);
        try {
            assertThat(alerts.raise(type, AlertLevel.INFO, "hello")).isNotNull();
            assertThat(alerts.raise(type, AlertLevel.INFO, "hello")).isNull();
            assertThat(jdbc.queryForObject("select \"dedupKey\" from admin_alert where type = ?", String.class, type))
                    .isEqualTo(type);
            assertThat(jdbc.queryForObject(
                            "select channels::text from notification where title = ? and \"userId\" = ?",
                            String.class,
                            "[INFO] " + type,
                            admin))
                    .isEqualTo("[]");
        } finally {
            jdbc.update("delete from notification_setting where \"userId\" = ? and category = 'SYSTEM'", admin);
        }
    }

    @Test
    @DisplayName("写入失败只记日志、返回 null，不抛错")
    void failureIsSwallowed() {
        String tooLong = "x".repeat(51);
        assertThat(alerts.raise(tooLong, AlertLevel.WARN, "m", null, "k-" + UUID.randomUUID()))
                .isNull();
        assertThat(metrics.value("admin_alerts_total", tooLong, "WARN")).isZero();
    }
}
