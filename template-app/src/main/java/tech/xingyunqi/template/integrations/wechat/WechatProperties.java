package tech.xingyunqi.template.integrations.wechat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code WECHAT_APP_ID} / {@code WECHAT_APP_SECRET}（APP OAuth 登录）、{@code WECHAT_MINI_APP_ID} /
 * {@code WECHAT_MINI_APP_SECRET}（小程序）； {@code apiBase} 为微信接口根地址；{@code WECHAT_MINI_PROGRAM_STATE}、
 * {@code WECHAT_SUBSCRIBE_ORDER_PAID_TEMPLATE_ID} / {@code WECHAT_SUBSCRIBE_ORDER_PAID_FIELDS}（订阅消息）。
 */
@ConfigurationProperties("app.wechat")
public record WechatProperties(
        @Nullable String appId,
        @Nullable String appSecret,
        @Nullable String miniAppId,
        @Nullable String miniAppSecret,
        @Nullable String apiBase,
        @Nullable String miniProgramState,
        @Nullable String subscribeOrderPaidTemplateId,
        @Nullable String subscribeOrderPaidFields) {

    public static final String DEFAULT_API_BASE = "https://api.weixin.qq.com";
    public static final String DEFAULT_MINI_PROGRAM_STATE = "formal";
    static final Set<String> MINI_PROGRAM_STATES = Set.of("developer", "trial", DEFAULT_MINI_PROGRAM_STATE);
    static final Pattern TEMPLATE_FIELDS = Pattern.compile("^\\s*\\w+\\s*:\\s*\\w+\\s*(,\\s*\\w+\\s*:\\s*\\w+\\s*)*$");

    /** 订阅消息场景的模板：{@code fields} 为业务字段到模板字段 key 的映射。 */
    public record SubscribeTemplate(String templateId, Map<String, String> fields) {}

    public WechatProperties {
        if (miniProgramState != null
                && !miniProgramState.isEmpty()
                && !MINI_PROGRAM_STATES.contains(miniProgramState)) {
            throw new IllegalArgumentException(
                    "WECHAT_MINI_PROGRAM_STATE must be one of developer, trial, formal; got \"" + miniProgramState
                            + "\"");
        }
        if (subscribeOrderPaidFields != null
                && !subscribeOrderPaidFields.isEmpty()
                && !TEMPLATE_FIELDS.matcher(subscribeOrderPaidFields).matches()) {
            throw new IllegalArgumentException(
                    "WECHAT_SUBSCRIBE_ORDER_PAID_FIELDS must be like orderNo:character_string1,amount:amount2");
        }
    }

    public boolean configured() {
        return appId != null && !appId.isEmpty() && appSecret != null && !appSecret.isEmpty();
    }

    public String apiBaseOrDefault() {
        return apiBase == null || apiBase.isEmpty() ? DEFAULT_API_BASE : apiBase;
    }

    public String miniProgramStateOrDefault() {
        return miniProgramState == null || miniProgramState.isEmpty() ? DEFAULT_MINI_PROGRAM_STATE : miniProgramState;
    }

    /** 按场景配置的订阅消息模板，key 与通知 {@code wechatSubscribe.scene} 取值一致。 */
    public Map<String, SubscribeTemplate> subscribeTemplates() {
        return Map.of(
                "orderPaid",
                new SubscribeTemplate(
                        subscribeOrderPaidTemplateId == null ? "" : subscribeOrderPaidTemplateId,
                        parseTemplateFields(subscribeOrderPaidFields)));
    }

    /** {@code orderNo:character_string1,amount:amount2} → {@code {orderNo=character_string1, amount=amount2}}。 */
    static Map<String, String> parseTemplateFields(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : value.split(",")) {
            String[] parts = pair.split(":");
            fields.put(parts[0].strip(), parts[1].strip());
        }
        return Collections.unmodifiableMap(fields);
    }
}
