package tech.xingyunqi.template.common.web.route;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 需要有效 refresh 令牌（nestjs {@code AuthGuard('jwt-refresh')}）。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RefreshTokenAuth {}
