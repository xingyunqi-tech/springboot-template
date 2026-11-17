package tech.xingyunqi.template.modules.config;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public interface ConfigRepository {

    Optional<ConfigEntry> findByKey(String key);

    /** 新建时 {@code isPublic} 为 null 视为 false，更新时为 null 保持不变；取值与可见性都未变化时不写库。返回的 value 为传入值。 */
    ConfigEntry upsert(String key, JsonNode value, @Nullable Boolean isPublic);
}
