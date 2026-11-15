package tech.xingyunqi.template.infrastructure.throttler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tech.xingyunqi.template.common.core.exception.TooManyRequestsException;
import tech.xingyunqi.template.common.oss.FileDriver;
import tech.xingyunqi.template.common.redis.throttle.Gcra;
import tech.xingyunqi.template.common.redis.throttle.RateLimiter;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tech.xingyunqi.template.common.security.route.Claims;
import tech.xingyunqi.template.common.security.route.RouteAuth;
import tech.xingyunqi.template.common.web.http.ClientInfo;
import tech.xingyunqi.template.common.web.route.SkipThrottle;
import tech.xingyunqi.template.common.web.route.Throttle;
import tools.jackson.databind.JsonNode;

/**
 * 全局限流守卫（nestjs {@code UserThrottlerGuard}）：handler 解析后、鉴权前执行；{@code @AuthenticatedOnly} 路由带有效 access 令牌时按
 * {@code user:<id>} 计数，其余按 {@code ip:<normalizeIp>} 计数。放行时写 {@code X-RateLimit-*}，超限抛 429 并只带 {@code Retry-After}。
 */
public class ThrottleGuard implements HandlerInterceptor {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String RESET_HEADER = "X-RateLimit-Reset";
    static final String UPLOAD_OPERATION = "uploadFile";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Map<FileDriver, String> UPLOAD_HANDLERS = Map.of(
            FileDriver.LOCAL, "FilesLocalController-uploadFile",
            FileDriver.S3, "FilesS3Controller-uploadFile",
            FileDriver.S3_PRESIGNED, "FilesS3PresignedController-uploadFile");

    record Rule(String handler, int limit, long ttlMs, boolean perUser) {}

    private final ThrottlerProperties properties;
    private final RateLimiter limiter;
    private final Jwt jwt;
    private final String accessSecret;
    private final int trustProxy;
    private final Map<String, String> nestHandlers;
    private final FileDriver fileDriver;
    private final Map<HandlerMethod, Rule> rules = new ConcurrentHashMap<>();
    private final Map<HandlerMethod, Boolean> skipped = new ConcurrentHashMap<>();

    public ThrottleGuard(
            ThrottlerProperties properties,
            RateLimiter limiter,
            Jwt jwt,
            String accessSecret,
            int trustProxy,
            Map<String, String> nestHandlers,
            FileDriver fileDriver) {
        this.properties = properties;
        this.limiter = limiter;
        this.jwt = jwt;
        this.accessSecret = accessSecret;
        this.trustProxy = trustProxy;
        this.nestHandlers = Map.copyOf(nestHandlers);
        this.fileDriver = fileDriver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled()
                || !(handler instanceof HandlerMethod method)
                || skipped.computeIfAbsent(method, ThrottleGuard::skips)) {
            return true;
        }
        Rule rule = rules.computeIfAbsent(method, this::rule);
        String key = RateLimiter.key(rule.handler(), tracker(request, rule));
        Gcra.Decision decision = limiter.consume(key, rule.ttlMs() * Gcra.MICROS_PER_MILLI, rule.limit());
        if (!decision.allowed()) {
            throw new TooManyRequestsException(
                    Map.of(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds())));
        }
        response.setHeader(LIMIT_HEADER, Integer.toString(rule.limit()));
        response.setHeader(REMAINING_HEADER, Long.toString(decision.remaining()));
        response.setHeader(RESET_HEADER, Long.toString(decision.resetSeconds()));
        return true;
    }

    private static boolean skips(HandlerMethod method) {
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), SkipThrottle.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), SkipThrottle.class);
    }

    Rule rule(HandlerMethod method) {
        Throttle declared = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), Throttle.class);
        if (declared == null) {
            declared = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), Throttle.class);
        }
        int limit = declared != null ? declared.limit() : properties.limit();
        long ttlMs = declared != null ? declared.ttl() : properties.ttl() * 1000L;
        boolean perUser = RouteAuth.of(method).mode() == RouteAuth.Mode.JWT;
        return new Rule(handlerName(method.getMethod().getName()), limit, ttlMs, perUser);
    }

    /** nestjs 的 {@code <Controller>-<handler>}，按 operationId（即 handler 方法名）查表，表外回落为 operationId。 */
    String handlerName(String operationId) {
        if (UPLOAD_OPERATION.equals(operationId)) {
            return UPLOAD_HANDLERS.getOrDefault(fileDriver, operationId);
        }
        return nestHandlers.getOrDefault(operationId, operationId);
    }

    String tracker(HttpServletRequest request, Rule rule) {
        if (rule.perUser()) {
            String userId = bearerUserId(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (userId != null) {
                return "user:" + userId;
            }
        }
        String ip = ClientInfo.clientIp(request, trustProxy);
        return "ip:" + ClientIps.normalize(ip == null ? "" : ip);
    }

    private @Nullable String bearerUserId(@Nullable String authorization) {
        String[] parts = WHITESPACE.split(authorization == null ? "" : authorization.strip(), 3);
        if (!"bearer".equals(parts[0].toLowerCase(Locale.ROOT)) || parts.length < 2 || parts[1].isEmpty()) {
            return null;
        }
        JsonNode id;
        try {
            id = jwt.verify(parts[1], accessSecret).get("id");
        } catch (RuntimeException e) {
            return null;
        }
        return id == null || id.isNull() ? null : Claims.jsString(id);
    }
}
