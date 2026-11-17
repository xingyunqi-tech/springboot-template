package tech.xingyunqi.template.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import tech.xingyunqi.template.infrastructure.health.HealthProperties;
import tech.xingyunqi.template.integrations.alipay.AlipayProperties;
import tech.xingyunqi.template.integrations.sms.SmsProperties;
import tech.xingyunqi.template.integrations.wechat.WechatProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    HealthProperties.class,
    WechatProperties.class,
    AlipayProperties.class,
    SmsProperties.class
})
class IntegrationPropertiesConfiguration {}
