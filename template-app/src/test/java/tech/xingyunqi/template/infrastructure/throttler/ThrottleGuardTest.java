package tech.xingyunqi.template.infrastructure.throttler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.common.oss.FileDriver;
import tech.xingyunqi.template.common.redis.throttle.RateLimiter;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tech.xingyunqi.template.common.web.route.Throttle;
import tools.jackson.databind.json.JsonMapper;

class ThrottleGuardTest {

    static final String SECRET = "access-secret";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    static final Jwt JWT = new Jwt(JsonMapper.builder().build(), CLOCK);
    static final ThrottleGuard.Rule JWT_RULE = new ThrottleGuard.Rule("AuthController-me", 100, 60_000, true);
    static final ThrottleGuard.Rule PUBLIC_RULE = new ThrottleGuard.Rule("HomeController-appInfo", 100, 60_000, false);

    static ThrottleGuard guard(int trustProxy, String fileDriver) {
        return guard(trustProxy, FileDriver.parse(fileDriver));
    }

    static ThrottleGuard guard(int trustProxy, FileDriver fileDriver) {
        return new ThrottleGuard(
                new ThrottlerProperties("true", 60, 100),
                new RateLimiter(null, null, new AppMetrics("app"), () -> null, CLOCK),
                JWT,
                SECRET,
                trustProxy,
                ThrottlerConfiguration.nestHandlers(JsonMapper.builder().build()),
                fileDriver);
    }

    static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("203.0.113.7");
        if (!authorization.isEmpty()) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    @DisplayName("按 operationId 查 nest_handlers.json，表外回落为 operationId，uploadFile 按 FILE_DRIVER 区分")
    void handlerNames() {
        ThrottleGuard local = guard(0, "local");
        assertThat(local.handlerName("loginWithEmail")).isEqualTo("AuthController-login");
        assertThat(local.handlerName("healthCheck")).isEqualTo("HealthController-check");
        assertThat(local.handlerName("getMetrics")).isEqualTo("MetricsController-index");
        assertThat(local.handlerName("downloadFile")).isEqualTo("FilesLocalController-download");
        assertThat(local.handlerName("notInTable")).isEqualTo("notInTable");
        assertThat(local.handlerName("uploadFile")).isEqualTo("FilesLocalController-uploadFile");
        assertThat(guard(0, "s3").handlerName("uploadFile")).isEqualTo("FilesS3Controller-uploadFile");
        assertThat(guard(0, "s3-presigned").handlerName("uploadFile"))
                .isEqualTo("FilesS3PresignedController-uploadFile");
    }

    @Test
    @DisplayName("auth 路由级规则与 nestjs @Throttle 声明一致，其余路由用默认规则")
    void authRouteRules() throws ClassNotFoundException {
        Map<String, String> declared = new TreeMap<>();
        for (Method method : Class.forName("tech.xingyunqi.template.core.auth.web.AuthController")
                .getDeclaredMethods()) {
            Throttle throttle = method.getAnnotation(Throttle.class);
            if (throttle != null) {
                declared.put(method.getName(), throttle.limit() + "/" + throttle.ttl());
            }
        }
        assertThat(declared)
                .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                        Map.entry("loginWithEmail", "5/60000"),
                        Map.entry("forgotPassword", "3/300000"),
                        Map.entry("resetPassword", "5/300000"),
                        Map.entry("updateCurrentUser", "10/60000"),
                        Map.entry("changePassword", "3/60000"),
                        Map.entry("sendSmsCode", "3/60000"),
                        Map.entry("loginWithPhone", "5/60000"),
                        Map.entry("loginWithSms", "5/60000"),
                        Map.entry("registerWithPhone", "3/60000"),
                        Map.entry("resetPasswordByPhone", "5/300000"),
                        Map.entry("loginWithWechat", "10/60000"),
                        Map.entry("loginWithWechatPhone", "10/60000"),
                        Map.entry("loginWithAlipay", "10/60000")));
    }

    @Test
    @DisplayName("JWT 路由带有效令牌按 user:<id> 计数，令牌无效、过期或 scheme 不符时按 IP")
    void trackerForJwtRoutes() {
        ThrottleGuard guard = guard(0, "local");
        String valid = JWT.sign(Map.of("id", 42, "sessionId", 1), SECRET, "15m");
        String expired = JWT.sign(Map.of("id", 42), SECRET, "-1s");
        String otherSecret = JWT.sign(Map.of("id", 42), "other", "15m");
        String withoutId = JWT.sign(Map.of("sessionId", 1), SECRET, "15m");

        assertThat(guard.tracker(request("Bearer " + valid), JWT_RULE)).isEqualTo("user:42");
        assertThat(guard.tracker(request("  bearer   " + valid + " extra"), JWT_RULE))
                .isEqualTo("user:42");
        assertThat(guard.tracker(request("Bearer " + expired), JWT_RULE)).isEqualTo("ip:203.0.113.7");
        assertThat(guard.tracker(request("Bearer " + otherSecret), JWT_RULE)).isEqualTo("ip:203.0.113.7");
        assertThat(guard.tracker(request("Bearer " + withoutId), JWT_RULE)).isEqualTo("ip:203.0.113.7");
        assertThat(guard.tracker(request("Basic " + valid), JWT_RULE)).isEqualTo("ip:203.0.113.7");
        assertThat(guard.tracker(request("Bearer"), JWT_RULE)).isEqualTo("ip:203.0.113.7");
        assertThat(guard.tracker(request(""), JWT_RULE)).isEqualTo("ip:203.0.113.7");
        assertThat(guard.tracker(request("Bearer " + valid), PUBLIC_RULE)).isEqualTo("ip:203.0.113.7");
    }

    @Test
    @DisplayName("TRUST_PROXY 跳数决定是否采用 X-Forwarded-For，IPv6 按 /64 归并")
    void trackerForClientIp() {
        MockHttpServletRequest request = request("");
        request.addHeader("X-Forwarded-For", "2001:db8:abcd:12:1:2:3:4");
        assertThat(guard(0, "local").tracker(request, PUBLIC_RULE)).isEqualTo("ip:203.0.113.7");
        assertThat(guard(1, "local").tracker(request, PUBLIC_RULE)).isEqualTo("ip:2001:db8:abcd:12::/64");
    }
}
