package tech.xingyunqi.template.infrastructure.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * nestjs {@code @Auditable}：handler 成功返回后写一条 {@code audit_logs}，写入失败只记日志、不影响响应。 {@code entityIdFrom = PARAM} 先取路由参数（多个以
 * {@code :} 连接）再取响应 {@code id}，{@code RESPONSE} 相反，都没有时为 {@code unknown}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audit {

    AuditAction action();

    String entityType();

    String[] idParam() default {"id"};

    EntityIdFrom entityIdFrom() default EntityIdFrom.PARAM;

    boolean includeBody() default true;

    boolean includeResponse() default false;

    Class<? extends AuditBodySummarizer> summarizeBody() default AuditBodySummarizer.None.class;

    /** 实体 id 的取值来源。 */
    enum EntityIdFrom {
        PARAM,
        RESPONSE
    }
}
