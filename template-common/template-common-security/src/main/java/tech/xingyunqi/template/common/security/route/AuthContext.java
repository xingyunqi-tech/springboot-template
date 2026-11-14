package tech.xingyunqi.template.common.security.route;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 当前请求经路由鉴权得到的主体。 */
public final class AuthContext {

    static final String PRINCIPAL = AuthContext.class.getName() + ".principal";
    static final String REFRESH = AuthContext.class.getName() + ".refresh";

    private AuthContext() {}

    public static AuthPrincipal current() {
        AuthPrincipal principal = optional();
        if (principal == null) {
            throw new IllegalStateException("route is not declared with @AuthenticatedOnly");
        }
        return principal;
    }

    public static @Nullable AuthPrincipal optional() {
        return (AuthPrincipal) request().getAttribute(PRINCIPAL);
    }

    public static RefreshPrincipal refresh() {
        RefreshPrincipal principal = (RefreshPrincipal) request().getAttribute(REFRESH);
        if (principal == null) {
            throw new IllegalStateException("route is not declared with @RefreshTokenAuth");
        }
        return principal;
    }

    private static HttpServletRequest request() {
        if (RequestContextHolder.currentRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        throw new IllegalStateException("no servlet request bound to the current thread");
    }
}
