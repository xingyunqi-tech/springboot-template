package tech.xingyunqi.template.common.security.route;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import tech.xingyunqi.template.common.web.route.AuthenticatedOnly;
import tech.xingyunqi.template.common.web.route.MonitoringToken;
import tech.xingyunqi.template.common.web.route.OptionalAuth;
import tech.xingyunqi.template.common.web.route.PermissionsGuarded;
import tech.xingyunqi.template.common.web.route.PublicEndpoint;
import tech.xingyunqi.template.common.web.route.RefreshTokenAuth;
import tech.xingyunqi.template.common.web.route.RequireAnyPermission;
import tech.xingyunqi.template.common.web.route.RequirePermissions;

/** handler 上声明的鉴权方式与权限要求；方法级注解优先于类级。 */
public record RouteAuth(Mode mode, boolean guarded, List<String> permissions, boolean anyOf) {

    /** 鉴权方式。 */
    public enum Mode {
        PUBLIC,
        OPTIONAL,
        JWT,
        REFRESH,
        MONITORING
    }

    public static RouteAuth of(HandlerMethod handler) {
        List<Mode> modes = modes(handler);
        if (modes.size() != 1) {
            throw new IllegalStateException("handler " + handler
                    + " must declare exactly one of @PublicEndpoint, @OptionalAuth, @AuthenticatedOnly,"
                    + " @RefreshTokenAuth, @MonitoringToken; found " + modes);
        }
        boolean guarded = find(handler, PermissionsGuarded.class) != null;
        Permissions onMethod = permissions(
                AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequirePermissions.class),
                AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequireAnyPermission.class),
                handler);
        Permissions declared = onMethod != null
                ? onMethod
                : permissions(
                        AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), RequirePermissions.class),
                        AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), RequireAnyPermission.class),
                        handler);
        List<String> permissions = declared == null ? List.of() : declared.values();
        boolean anyOf = declared != null && declared.anyOf();
        return new RouteAuth(modes.getFirst(), guarded, permissions, anyOf);
    }

    private record Permissions(List<String> values, boolean anyOf) {}

    private static @Nullable Permissions permissions(
            @Nullable RequirePermissions all, @Nullable RequireAnyPermission any, HandlerMethod handler) {
        if (all != null && any != null) {
            throw new IllegalStateException(
                    "handler " + handler + " declares both @RequirePermissions and @RequireAnyPermission at one level");
        }
        if (all != null) {
            return new Permissions(List.of(all.value()), false);
        }
        return any == null ? null : new Permissions(List.of(any.value()), true);
    }

    private static List<Mode> modes(HandlerMethod handler) {
        List<Mode> methodLevel = declared(handler, true);
        return methodLevel.isEmpty() ? declared(handler, false) : methodLevel;
    }

    private static List<Mode> declared(HandlerMethod handler, boolean method) {
        List<Mode> modes = new ArrayList<>();
        if (has(handler, PublicEndpoint.class, method)) {
            modes.add(Mode.PUBLIC);
        }
        if (has(handler, OptionalAuth.class, method)) {
            modes.add(Mode.OPTIONAL);
        }
        if (has(handler, AuthenticatedOnly.class, method)) {
            modes.add(Mode.JWT);
        }
        if (has(handler, RefreshTokenAuth.class, method)) {
            modes.add(Mode.REFRESH);
        }
        if (has(handler, MonitoringToken.class, method)) {
            modes.add(Mode.MONITORING);
        }
        return modes;
    }

    private static boolean has(
            HandlerMethod handler, Class<? extends java.lang.annotation.Annotation> type, boolean method) {
        return method
                ? AnnotatedElementUtils.hasAnnotation(handler.getMethod(), type)
                : AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), type);
    }

    private static <A extends java.lang.annotation.Annotation> @Nullable A find(HandlerMethod handler, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), type);
    }
}
