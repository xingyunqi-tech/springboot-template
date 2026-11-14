package tech.xingyunqi.template.common.security.route;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 按 handler 上的声明执行鉴权与权限守卫，顺序与 nestjs 守卫一致：先于参数校验执行。 */
public class RouteAuthInterceptor implements HandlerInterceptor {

    private static final Pattern BEARER = Pattern.compile("(\\S+)\\s+(\\S+)");

    private final Jwt jwt;
    private final AuthProperties properties;
    private final AccessTokenValidator validator;
    private final PermissionResolver permissions;
    private final Map<HandlerMethod, RouteAuth> routes = new ConcurrentHashMap<>();

    public RouteAuthInterceptor(
            Jwt jwt, AuthProperties properties, AccessTokenValidator validator, PermissionResolver permissions) {
        this.jwt = jwt;
        this.properties = properties;
        this.validator = validator;
        this.permissions = permissions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RouteAuth route = routes.computeIfAbsent(method, RouteAuth::of);
        AuthPrincipal principal = switch (route.mode()) {
            case PUBLIC -> null;
            case OPTIONAL -> optional(request);
            case JWT -> validator.validate(verify(bearer(request), properties.secret()));
            case REFRESH -> {
                request.setAttribute(AuthContext.REFRESH, refresh(request));
                yield null;
            }
        };
        if (principal != null) {
            request.setAttribute(AuthContext.PRINCIPAL, principal);
        }
        if (route.guarded()) {
            checkPermissions(route, principal);
        }
        return true;
    }

    private @Nullable AuthPrincipal optional(HttpServletRequest request) {
        String token = bearer(request);
        if (token == null) {
            return null;
        }
        try {
            return validator.validate(jwt.verify(token, properties.secret()));
        } catch (Jwt.InvalidTokenException | HttpStatusException e) {
            return null;
        }
    }

    private RefreshPrincipal refresh(HttpServletRequest request) {
        ObjectNode payload = verify(bearer(request), properties.refreshSecret());
        JsonNode sessionId = payload.get("sessionId");
        Integer id = Claims.positiveInt(sessionId);
        if (id == null) {
            throw HttpStatusException.unauthorized(null);
        }
        return new RefreshPrincipal(id, Claims.jsString(payload.get("hash")));
    }

    private ObjectNode verify(@Nullable String token, String secret) {
        if (token == null) {
            throw HttpStatusException.unauthorized(null);
        }
        try {
            return jwt.verify(token, secret);
        } catch (Jwt.InvalidTokenException e) {
            throw HttpStatusException.unauthorized(null);
        }
    }

    private void checkPermissions(RouteAuth route, @Nullable AuthPrincipal principal) {
        List<String> required = route.permissions();
        if (required.isEmpty()) {
            throw HttpStatusException.forbidden("No permission declared for this route");
        }
        if (principal == null) {
            throw HttpStatusException.forbidden("User not authenticated");
        }
        Integer roleId = principal.roleId();
        if (roleId == null) {
            throw HttpStatusException.forbidden("User role not found");
        }
        Collection<String> granted = permissions.permissionsForRole(roleId);
        if (route.anyOf()) {
            if (required.stream().noneMatch(granted::contains)) {
                throw HttpStatusException.forbidden("Missing any of permissions: " + String.join(", ", required));
            }
            return;
        }
        List<String> missing =
                required.stream().filter(p -> !granted.contains(p)).toList();
        if (!missing.isEmpty()) {
            throw HttpStatusException.forbidden("Missing permissions: " + String.join(", ", missing));
        }
    }

    static @Nullable String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        Matcher matcher = BEARER.matcher(header);
        if (!matcher.find() || !"bearer".equals(matcher.group(1).toLowerCase(Locale.ROOT))) {
            return null;
        }
        return matcher.group(2);
    }
}
