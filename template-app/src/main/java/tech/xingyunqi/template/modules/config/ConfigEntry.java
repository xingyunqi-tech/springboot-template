package tech.xingyunqi.template.modules.config;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** 一条配置；未写入过的登记项以默认值呈现，时间为 null。 */
public record ConfigEntry(
        String key,
        JsonNode value,
        boolean isPublic,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public ConfigEntry withPublic(boolean visible) {
        return new ConfigEntry(key, value, visible, createdAt, updatedAt);
    }
}
