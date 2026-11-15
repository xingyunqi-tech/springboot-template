package tech.xingyunqi.template.common.web.route;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 路由级限流规则（nestjs {@code @Throttle({default: {limit, ttl}})}），{@code ttl} 为毫秒。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Throttle {

    int limit();

    long ttl();
}
