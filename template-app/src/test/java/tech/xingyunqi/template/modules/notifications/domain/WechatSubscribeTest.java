package tech.xingyunqi.template.modules.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WechatSubscribeTest {

    @Test
    void buildReturnsNullWithoutTemplateId() {
        WechatSubscribe.Payload payload = new WechatSubscribe.Payload("orderPaid", Map.of(), null);
        assertThat(WechatSubscribe.build(payload, null)).isNull();
        assertThat(WechatSubscribe.build(payload, new WechatSubscribe.Template(null, Map.of())))
                .isNull();
        assertThat(WechatSubscribe.build(payload, new WechatSubscribe.Template("", Map.of())))
                .isNull();
    }

    @Test
    void buildMapsConfiguredFieldsAndTruncatesByType() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("orderNo", "character_string1");
        fields.put("amount", "amount2");
        fields.put("missing", "thing3");
        WechatSubscribe.Payload payload =
                new WechatSubscribe.Payload("orderPaid", Map.of("orderNo", "x".repeat(40), "amount", "9.00元"), "p");
        WechatSubscribe.Message message = WechatSubscribe.build(payload, new WechatSubscribe.Template("tpl", fields));
        assertThat(message).isNotNull();
        assertThat(message.templateId()).isEqualTo("tpl");
        assertThat(message.page()).isEqualTo("p");
        assertThat(message.data())
                .containsExactly(Map.entry("character_string1", "x".repeat(32)), Map.entry("amount2", "9.00元"));
    }

    @Test
    void formatValueCountsCodePoints() {
        assertThat(WechatSubscribe.formatValue("name1", "😀".repeat(12))).isEqualTo("😀".repeat(10));
        assertThat(WechatSubscribe.formatValue("thing1", "short")).isEqualTo("short");
        assertThat(WechatSubscribe.formatValue("time1", "x".repeat(100))).hasSize(100);
    }
}
