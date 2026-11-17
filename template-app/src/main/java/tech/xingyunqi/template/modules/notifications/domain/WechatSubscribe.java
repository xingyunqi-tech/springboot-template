package tech.xingyunqi.template.modules.notifications.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** 通知 {@code metadata.wechatSubscribe} 到微信订阅消息的映射（nestjs {@code wechat-subscribe.ts}）。 */
public final class WechatSubscribe {

    /** 订阅消息场景，取值与 {@code wechat.subscribeTemplates} 的 key 一致。 */
    public static final String ORDER_PAID = "orderPaid";

    /** 写入通知 metadata、由队列发送的订阅消息参数；{@code values} 为已格式化的业务字段值。 */
    public record Payload(
            String scene,
            Map<String, String> values,
            @Nullable String page) {}

    /** 场景的模板配置：{@code fields} 为业务字段到模板字段 key 的映射。 */
    public record Template(@Nullable String templateId, Map<String, String> fields) {}

    public record Message(String templateId, @Nullable String page, Map<String, String> data) {}

    /** 订阅消息各字段类型的长度上限，按模板字段 key 的前缀匹配。 */
    private static final List<Map.Entry<String, Integer>> FIELD_MAX_LENGTH = List.of(
            Map.entry("character_string", 32),
            Map.entry("car_number", 8),
            Map.entry("thing", 20),
            Map.entry("number", 32),
            Map.entry("letter", 32),
            Map.entry("symbol", 5),
            Map.entry("phrase", 5),
            Map.entry("name", 10));

    private WechatSubscribe() {}

    /** 按模板配置把业务字段映射为模板字段，模板未配置时返回 null。 */
    public static @Nullable Message build(Payload payload, @Nullable Template template) {
        if (template == null
                || template.templateId() == null
                || template.templateId().isEmpty()) {
            return null;
        }
        Map<String, String> data = new LinkedHashMap<>();
        template.fields().forEach((field, key) -> {
            String value = payload.values().get(field);
            if (value != null) {
                data.put(key, formatValue(key, value));
            }
        });
        return new Message(template.templateId(), payload.page(), data);
    }

    /** 按模板字段类型截断取值（按 Unicode 码点计数）。 */
    public static String formatValue(String key, String value) {
        for (Map.Entry<String, Integer> rule : FIELD_MAX_LENGTH) {
            if (key.startsWith(rule.getKey())) {
                int max = rule.getValue();
                return value.codePointCount(0, value.length()) <= max
                        ? value
                        : value.substring(0, value.offsetByCodePoints(0, max));
            }
        }
        return value;
    }
}
