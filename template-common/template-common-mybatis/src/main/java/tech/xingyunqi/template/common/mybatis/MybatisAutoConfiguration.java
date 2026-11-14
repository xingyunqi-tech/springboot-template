package tech.xingyunqi.template.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 连接超时校验、事务通知顺序（{@link WriteTransactionInterceptor} 在事务内层）与 {@code updatedAt} 自动填充。 */
@AutoConfiguration(before = TransactionAutoConfiguration.class)
@EnableConfigurationProperties(DatabaseProperties.class)
@EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE + 100, proxyTargetClass = true)
public class MybatisAutoConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor writeTransactionAdvisor() {
        ComposablePointcut pointcut = new ComposablePointcut(
                        AnnotationMatchingPointcut.forClassAnnotation(WriteTransaction.class))
                .union(AnnotationMatchingPointcut.forMethodAnnotation(WriteTransaction.class));
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, new WriteTransactionInterceptor());
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 101);
        return advisor;
    }

    @Bean
    WriteTransactions writeTransactions() {
        return new WriteTransactions();
    }

    @Bean
    MetaObjectHandler timestampMetaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {}

            @Override
            public void updateFill(MetaObject metaObject) {
                setFieldValByName("updatedAt", LocalDateTime.now(ZoneOffset.UTC), metaObject);
            }
        };
    }
}
