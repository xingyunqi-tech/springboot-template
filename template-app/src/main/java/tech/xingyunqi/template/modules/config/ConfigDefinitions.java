package tech.xingyunqi.template.modules.config;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.IntNode;

/** 远程配置项登记表（nestjs {@code CONFIG_DEFINITIONS}），配置值统一存为 {@code {"value": <配置值>}}。 */
public final class ConfigDefinitions {

    /** {@code defaultValue} 为 null 表示无默认值；{@code dedicated} 的登记项只能经专用接口写入。 */
    public record Definition(
            String key,
            String type,
            @Nullable JsonNode defaultValue,
            boolean isPublic,
            boolean dedicated,
            String description) {}

    public static final String APP_PROFILE = "app.profile";

    static final List<Definition> ALL = List.of(
            new Definition("ai.enabled", "boolean", BooleanNode.TRUE, true, false, "AI 功能总开关"),
            new Definition("ai.dailyBudget", "number", null, false, false, "全站每日成本上限（元），超过即熔断"),
            new Definition("ai.quota.chat", "number", IntNode.valueOf(20), true, false, "用户每日讲题/对话次数"),
            new Definition("ai.quota.grading", "number", IntNode.valueOf(3), true, false, "用户每日批改次数"),
            new Definition("ai.ratePerMinute", "number", IntNode.valueOf(10), false, false, "每用户每分钟 AI 请求上限"),
            new Definition("ai.paperGradingLimit", "number", IntNode.valueOf(5), false, false, "每份试卷自动批改主观题上限"),
            new Definition("ai.model", "string", null, false, false, "当前使用的模型标识"),
            new Definition("wrongBook.masterThreshold", "number", IntNode.valueOf(3), true, false, "错题连续答对阈值"),
            new Definition("practice.offlineSyncWindowMin", "number", IntNode.valueOf(5), false, false, "模考离线补交窗口（分钟）"),
            new Definition("practice.dailySetSize", "number", IntNode.valueOf(10), false, false, "每日一练题量"),
            new Definition("practice.trialSize", "number", IntNode.valueOf(3), true, false, "游客试做题数"),
            new Definition("paper.generateDailyLimit", "number", IntNode.valueOf(5), true, false, "每用户每日按规则生成试卷次数"),
            new Definition("consent.privacyPolicyVersion", "string", null, true, false, "隐私政策当前版本"),
            new Definition("consent.userAgreementVersion", "string", null, true, false, "用户协议当前版本"),
            new Definition("consent.aiServiceVersion", "string", null, true, false, "AI 服务声明当前版本"),
            new Definition("agreement.userAgreement", "string", null, true, false, "用户协议正文（HTML），未配置时小程序显示内置文本"),
            new Definition("agreement.privacyPolicy", "string", null, true, false, "隐私政策正文（HTML），未配置时小程序显示内置文本"),
            new Definition("agreement.aiService", "string", null, true, false, "AI 服务声明正文（HTML），未配置时小程序显示内置文本"),
            new Definition(APP_PROFILE, "json", null, false, true, "小程序站点信息（产品名称、主体、客服等），未配置的字段由小程序使用内置值"),
            new Definition("alert.thresholds", "json", null, false, false, "各监控项告警阈值"));

    private ConfigDefinitions() {}

    public static Optional<Definition> find(String key) {
        return ALL.stream().filter(definition -> definition.key().equals(key)).findFirst();
    }
}
