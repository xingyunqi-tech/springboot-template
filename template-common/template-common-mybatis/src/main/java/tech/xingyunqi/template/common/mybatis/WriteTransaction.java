package tech.xingyunqi.template.common.mybatis;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.transaction.annotation.Transactional;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

/** 写事务：成功提交；{@link HttpStatusException}（4xx 业务 / HTTP 错误）提交已写入的内容后再抛出，并丢弃 {@link AfterCommit} 登记的副作用；其它异常回滚。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Transactional(noRollbackFor = HttpStatusException.class)
public @interface WriteTransaction {

    @AliasFor(annotation = Transactional.class, attribute = "readOnly")
    boolean readOnly() default false;
}
