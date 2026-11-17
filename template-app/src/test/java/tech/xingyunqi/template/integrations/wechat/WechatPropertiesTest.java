package tech.xingyunqi.template.integrations.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class WechatPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WechatProperties.class)
    static class Config {}

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    @DisplayName("未设置时小程序版本为 formal，orderPaid 模板 ID 为空、字段映射为空")
    void defaults() {
        runner.withPropertyValues("app.wechat.mini-program-state=", "app.wechat.subscribe-order-paid-fields=")
                .run(context -> {
                    WechatProperties properties = context.getBean(WechatProperties.class);
                    assertThat(properties.miniProgramStateOrDefault()).isEqualTo("formal");
                    assertThat(properties.subscribeTemplates())
                            .containsExactly(
                                    Map.entry("orderPaid", new WechatProperties.SubscribeTemplate("", Map.of())));
                });
    }

    @Test
    @DisplayName("订阅消息字段解析为业务字段到模板字段 key 的有序映射，两侧空白被去掉")
    void parsesTemplateFields() {
        runner.withPropertyValues(
                        "app.wechat.mini-program-state=trial",
                        "app.wechat.subscribe-order-paid-template-id=TPL",
                        "app.wechat.subscribe-order-paid-fields= orderNo : character_string1 ,amount:amount2,paidAt:time3")
                .run(context -> {
                    WechatProperties properties = context.getBean(WechatProperties.class);
                    assertThat(properties.miniProgramStateOrDefault()).isEqualTo("trial");
                    WechatProperties.SubscribeTemplate template =
                            properties.subscribeTemplates().get("orderPaid");
                    assertThat(template.templateId()).isEqualTo("TPL");
                    assertThat(template.fields())
                            .containsExactly(
                                    Map.entry("orderNo", "character_string1"),
                                    Map.entry("amount", "amount2"),
                                    Map.entry("paidAt", "time3"));
                });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "app.wechat.mini-program-state=release",
                "app.wechat.mini-program-state=Formal",
                "app.wechat.subscribe-order-paid-fields=orderNo",
                "app.wechat.subscribe-order-paid-fields=orderNo:a,",
                "app.wechat.subscribe-order-paid-fields=order-no:a",
                "app.wechat.subscribe-order-paid-fields=orderNo:a:b"
            })
    @DisplayName("小程序版本不在 developer / trial / formal 内或订阅字段格式不合法时启动失败")
    void rejectsInvalidValues(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
