package tech.xingyunqi.template.integrations.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tech.xingyunqi.template.integrations.http.OutboundHttp;

class SmsPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SmsProperties.class)
    static class Config {}

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues(
                    "app.sms.code-expires=300",
                    "app.sms.code-length=6",
                    "app.sms.max-attempts=5",
                    "app.sms.resend-interval=60");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "app.sms.code-expires=59",
                "app.sms.code-expires=601",
                "app.sms.code-length=3",
                "app.sms.code-length=9",
                "app.sms.max-attempts=0",
                "app.sms.max-attempts=11",
                "app.sms.resend-interval=29",
                "app.sms.resend-interval=301"
            })
    @DisplayName("SMS_* 数值超出 nestjs 校验范围时启动失败")
    void rejectsOutOfRange(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("SMS_MOCK_MODE 只认字符串 true，endpoint 为空时取默认值")
    void mockModeAndEndpoint() {
        runner.withPropertyValues("app.sms.mock-mode=TRUE", "app.sms.endpoint=").run(context -> {
            SmsProperties properties = context.getBean(SmsProperties.class);
            assertThat(properties.mock()).isFalse();
            assertThat(properties.endpointOrDefault()).isEqualTo("dysmsapi.aliyuncs.com");
            assertThat(properties.hasCredentials()).isFalse();
        });
        runner.withPropertyValues("app.sms.mock-mode=true", "app.sms.access-key-id=a", "app.sms.access-key-secret=b")
                .run(context -> {
                    SmsProperties properties = context.getBean(SmsProperties.class);
                    assertThat(properties.mock()).isTrue();
                    assertThat(properties.hasCredentials()).isTrue();
                });
    }

    @Test
    @DisplayName("外呼错误消息去掉 URL query")
    void stripsQuery() {
        assertThat(OutboundHttp.stripQuery(
                        "ConnectException: https://api.weixin.qq.com/sns/jscode2session?appid=a&secret=s failed"))
                .isEqualTo("ConnectException: https://api.weixin.qq.com/sns/jscode2session failed");
        assertThat(OutboundHttp.stripQuery(null)).isEmpty();
    }
}
