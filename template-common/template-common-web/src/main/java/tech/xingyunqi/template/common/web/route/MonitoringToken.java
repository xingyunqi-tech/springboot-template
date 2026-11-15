package tech.xingyunqi.template.common.web.route;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 监控令牌（nestjs {@code MonitoringTokenGuard}）：配置了 {@code METRICS_TOKEN} 时要求 {@code Authorization: Bearer
 * <METRICS_TOKEN>}， 未配置时非生产放行、生产拒绝；拒绝为 403 {@code Forbidden resource}。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MonitoringToken {}
