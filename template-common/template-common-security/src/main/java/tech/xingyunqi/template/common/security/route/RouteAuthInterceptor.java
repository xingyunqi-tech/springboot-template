package tech.xingyunqi.template.common.security.route;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tech.xingyunqi.template.common.web.config.MetricsProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 按 handler 上的声明执行鉴权与权限守卫，顺序与 nestjs 守卫一致：先于参数校验执行。 */
public class RouteAuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_MDC_KEY = "user_id";
    static final String MONITORING_FORBIDDEN = "Forbidden resource";

    private static final Pattern BEARER = Pattern.compile("(\\S+)\\s+(\\S+)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Jwt jwt;
    private final AuthProperties properties;
    private final AccessTokenValidator validator;
    private final PermissionResolver permissions;
    private final MetricsProperties metrics;
    private final boolean production;
    private final Map<HandlerMethod, RouteAuth> routes = new ConcurrentHashMap<>();

    public RouteAuthInterceptor(
            Jwt jwt,
            AuthProperties properties,
            AccessTokenValidator validator,
            PermissionResolver permissions,
            MetricsProperties metrics,
            boolean production) {
        this.jwt = jwt;
        this.properties = properties;
        this.validator = validator;
        this.permissions = permissions;
        this.metrics = metrics;
        this.production = production;
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
            case MONITORING -> {
                checkMonitoringToken(request);
                yield null;
            }
        };
        if (principal != null) {
            request.setAttribute(AuthContext.PRINCIPAL, principal);
            MDC.put(USER_ID_MDC_KEY, Integer.toString(principal.id()));
        }
        if (route.guarded()) {
            checkPermissions(route, principal);
        }
        return true;
    }

    private void checkMonitoringToken(HttpServletRequest request) {
        String expected = metrics.configuredToken();
        boolean allowed = expected == null
                ? !production
                : monitoringTokenMatches(request.getHeader(HttpHeaders.AUTHORIZATION), expected);
        if (!allowed) {
            throw HttpStatusException.forbidden(MONITORING_FORBIDDEN);
        }
    }

    /** nestjs {@code MonitoringTokenGuard}：去掉首尾空白后按空白切分，scheme 不区分大小写，凭据常量时间比较。 */
    static boolean monitoringTokenMatches(@Nullable String authorization, String expected) {
        String[] parts = WHITESPACE.split(authorization == null ? "" : authorization.strip(), 3);
        String credentials = parts.length > 1 ? parts[1] : "";
        return "bearer".equals(parts[0].toLowerCase(Locale.ROOT))
                && MessageDigest.isEqual(
                        credentials.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
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
