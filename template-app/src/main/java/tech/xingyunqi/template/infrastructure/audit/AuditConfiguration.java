package tech.xingyunqi.template.infrastructure.audit;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class AuditConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor auditAdvisor(ObjectProvider<AuditService> audit, ObjectProvider<JsonMapper> json) {
        return new DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(Audit.class), new AuditInterceptor(audit, json));
    }
}
