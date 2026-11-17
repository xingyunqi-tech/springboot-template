package tech.xingyunqi.template.modules.appprofile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.json.JsStrings;
import tech.xingyunqi.template.modules.config.AppConfigService;
import tech.xingyunqi.template.modules.config.ConfigDefinitions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 小程序站点信息，存于配置项 {@code app.profile}；每个字段为去除首尾空白后的非空字符串，否则为 null。 */
@Service
public class AppProfileService {

    public static final List<String> FIELDS = List.of(
            "appName",
            "slogan",
            "companyName",
            "copyright",
            "icpLicense",
            "servicePhone",
            "serviceHours",
            "serviceEmail",
            "shareTitle");

    private final AppConfigService configs;

    public AppProfileService(AppConfigService configs) {
        this.configs = configs;
    }

    public Map<String, @Nullable String> get() {
        return profile(configs.getValue(ConfigDefinitions.APP_PROFILE));
    }

    /** 整体覆盖，未传的字段保存为 null。 */
    public Map<String, @Nullable String> update(@Nullable JsonNode input) {
        Map<String, @Nullable String> profile = profile(input);
        ObjectNode stored = JsonNodeFactory.instance.objectNode();
        ObjectNode value = stored.putObject("value");
        profile.forEach(value::put);
        configs.upsert(ConfigDefinitions.APP_PROFILE, stored, null);
        return profile;
    }

    static Map<String, @Nullable String> profile(@Nullable JsonNode source) {
        Map<String, @Nullable String> profile = new LinkedHashMap<>();
        for (String field : FIELDS) {
            JsonNode value = source == null || !source.isObject() ? null : source.get(field);
            String text = value != null && value.isString() ? JsStrings.trim(value.asString()) : "";
            profile.put(field, text.isEmpty() ? null : text);
        }
        return profile;
    }
}
