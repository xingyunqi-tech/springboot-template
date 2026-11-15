package tech.xingyunqi.template.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tech.xingyunqi.template.common.observability.metrics.AppMetrics;
import tech.xingyunqi.template.common.redis.throttle.Gcra;
import tech.xingyunqi.template.common.redis.throttle.RateLimiter;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tech.xingyunqi.template.common.security.route.AuthProperties;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

@SpringBootTest(
        properties = {
            "REDIS_ENABLED=true",
            "app.throttle.enabled=true",
            "app.throttle.limit=3",
            "app.trust-proxy=1",
            "HEALTH_MEMORY_HEAP_THRESHOLD_MB=100000"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ThrottleIT {

    static final List<String> RATE_HEADERS = List.of("X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset");
    static final AtomicInteger CLIENTS = new AtomicInteger(new SecureRandom().nextInt(1 << 22));
    static final long MINUTE = 60_000_000L;
    static final long FIXED_NOW = 268_382_706_344L;

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    Jwt jwt;

    @Autowired
    AuthProperties auth;

    @Autowired
    AppMetrics metrics;

    static String newIp() {
        int n = CLIENTS.incrementAndGet() & 0xFFFFFF;
        return "10." + (n >> 16) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }

    MockHttpServletResponse send(MockHttpServletRequestBuilder builder, String ip) throws Exception {
        return mvc.perform(builder.header("X-Forwarded-For", ip)).andReturn().getResponse();
    }

    MockHttpServletResponse get(String path, String ip) throws Exception {
        return send(MockMvcRequestBuilders.get(path), ip);
    }

    String token(int userId) {
        return "Bearer " + jwt.sign(Map.of("id", userId, "sessionId", 999_999), auth.secret(), "15m");
    }

    static int newUserId() {
        return 1_000_000 + CLIENTS.incrementAndGet();
    }

    static void assertNoRateHeaders(MockHttpServletResponse response) {
        for (String header : RATE_HEADERS) {
            assertThat(response.getHeader(header)).as(header).isNull();
        }
    }

    @Test
    @DisplayName("放行时带 X-RateLimit-*，超限 429 只带 Retry-After，body 为 tooManyRequests 译文")
    void defaultRuleSetsHeadersThenBlocks() throws Exception {
        String ip = newIp();
        for (int remaining = 2; remaining >= 0; remaining--) {
            MockHttpServletResponse response = get("/", ip);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("3");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo(Integer.toString(remaining));
            assertThat(Integer.parseInt(response.getHeader("X-RateLimit-Reset")))
                    .isBetween(1, 60);
            assertThat(response.getHeader("Retry-After")).isNull();
        }
        double before = metrics.value("http_requests_total", "GET", "/", "429");

        MockHttpServletResponse blocked = get("/", ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isBetween(1, 20);
        assertNoRateHeaders(blocked);
        assertThat(blocked.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":429,\"message\":\"Too many requests, please try again later\",\"data\":null}");
        assertThat(metrics.value("http_requests_total", "GET", "/", "429")).isEqualTo(before + 1);

        MockHttpServletResponse zh = send(MockMvcRequestBuilders.get("/").header("Accept-Language", "zh"), ip);
        assertThat(zh.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
    }

    @Test
    @DisplayName("路由级规则覆盖默认规则，且先于参数校验执行；校验失败的 422 同样带限流头")
    void routeRuleOverridesDefaultAndPrecedesValidation() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = send(
                    MockMvcRequestBuilders.post("/api/v1/auth/email/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"),
                    ip);
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("5");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo(Integer.toString(4 - i));
        }
        MockHttpServletResponse blocked = send(
                MockMvcRequestBuilders.post("/api/v1/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isBetween(1, 12);
    }

    @Test
    @DisplayName("各路由分别计数；未匹配路由与 OPTIONS 不计数、不带限流头")
    void routesAreCountedSeparately() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse unmatched = get("/api/v1/nope", ip);
            assertThat(unmatched.getStatus()).isEqualTo(404);
            assertNoRateHeaders(unmatched);
            MockHttpServletResponse preflight = send(MockMvcRequestBuilders.options("/"), ip);
            assertThat(preflight.getStatus()).isEqualTo(204);
            assertNoRateHeaders(preflight);
        }
        assertThat(get("/", ip).getHeader("X-RateLimit-Remaining")).isEqualTo("2");
        get("/", ip);
        get("/", ip);
        assertThat(get("/", ip).getStatus()).isEqualTo(429);
        MockHttpServletResponse health = get("/api/health", ip);
        assertThat(health.getStatus()).isEqualTo(200);
        assertThat(health.getHeader("X-RateLimit-Remaining")).isEqualTo("2");
    }

    @Test
    @DisplayName("JWT 路由带有效令牌按用户计数，跨 IP 共享配额；鉴权失败的 401 同样带限流头")
    void jwtRoutesCountPerUser() throws Exception {
        int userId = newUserId();
        String token = token(userId);
        for (int remaining = 2; remaining >= 0; remaining--) {
            MockHttpServletResponse response =
                    send(MockMvcRequestBuilders.get("/api/v1/auth/me").header("Authorization", token), newIp());
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo(Integer.toString(remaining));
        }
        assertThat(send(MockMvcRequestBuilders.get("/api/v1/auth/me").header("Authorization", token), newIp())
                        .getStatus())
                .isEqualTo(429);
        assertThat(redis.hasKey(RateLimiter.key("AuthController-me", "user:" + userId)))
                .isTrue();

        String ip = newIp();
        MockHttpServletResponse invalid =
                send(MockMvcRequestBuilders.get("/api/v1/auth/me").header("Authorization", "Bearer nope"), ip);
        assertThat(invalid.getStatus()).isEqualTo(401);
        assertThat(invalid.getHeader("X-RateLimit-Remaining")).isEqualTo("2");
        assertThat(redis.hasKey(RateLimiter.key("AuthController-me", "ip:" + ip)))
                .isTrue();
    }

    @Test
    @DisplayName("公开路由与监控令牌路由忽略令牌、按 IP 计数")
    void publicAndMonitoringRoutesCountPerIp() throws Exception {
        String token = token(newUserId());
        for (String path : List.of("/", "/metrics", "/api/v1/health")) {
            String first = newIp();
            String second = newIp();
            for (int i = 0; i < 3; i++) {
                send(MockMvcRequestBuilders.get(path).header("Authorization", token), first);
            }
            assertThat(send(MockMvcRequestBuilders.get(path).header("Authorization", token), first)
                            .getStatus())
                    .as(path)
                    .isEqualTo(429);
            MockHttpServletResponse other =
                    send(MockMvcRequestBuilders.get(path).header("Authorization", token), second);
            assertThat(other.getHeader("X-RateLimit-Remaining")).as(path).isEqualTo("2");
        }
        String ip = newIp();
        get("/metrics", ip);
        assertThat(redis.hasKey(RateLimiter.key("MetricsController-index", "ip:" + ip)))
                .isTrue();
    }

    @Test
    @DisplayName("local 驱动的 files 路由使用 nestjs 的 handler 键")
    void filesRoutesUseNestjsHandlerKeys() throws Exception {
        Map<String, String> routes = Map.of(
                "GET /api/v1/files/a.png", "FilesLocalController-download",
                "GET /api/v1/files/private/00000000-0000-0000-0000-000000000000", "FilesController-readPrivate",
                "POST /api/v1/files/upload", "FilesLocalController-uploadFile");
        for (Map.Entry<String, String> route : routes.entrySet()) {
            String ip = newIp();
            String[] parts = route.getKey().split(" ", 2);
            MockHttpServletResponse response = send(
                    MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1]),
                    ip);
            assertThat(response.getHeader("X-RateLimit-Limit"))
                    .as(route.getKey())
                    .isNotNull();
            assertThat(redis.hasKey(RateLimiter.key(route.getValue(), "ip:" + ip)))
                    .as(route.getKey())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("TRUST_PROXY=1 时按 X-Forwarded-For 最后一跳计数")
    void forwardedForWithTrustProxy() throws Exception {
        String shared = newIp();
        for (int i = 0; i < 3; i++) {
            get("/", newIp() + ", " + shared);
        }
        assertThat(get("/", "203.0.113.9, " + shared).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Redis 中写 nestjs 键加 :gcra 后缀，值为 3 位小数毫秒串，过期时间不超过周期；不触碰 nestjs 计数键")
    void storesTatUnderNestjsKey() throws Exception {
        String ip = newIp();
        String key = RateLimiter.key("HomeController-appInfo", "ip:" + ip);
        String counter = key.substring(0, key.length() - ":gcra".length());
        redis.opsForValue().set(counter, "7", 60, TimeUnit.SECONDS);
        redis.opsForValue().set(counter + ":block", "1", 60, TimeUnit.SECONDS);

        assertThat(get("/", ip).getStatus()).isEqualTo(200);
        get("/", ip);
        get("/", ip);
        assertThat(get("/", ip).getStatus()).isEqualTo(429);

        String stored = redis.opsForValue().get(key);
        assertThat(stored).matches("\\d+\\.\\d{3}");
        assertThat(Gcra.parseTat(stored) / 1_000_000L - System.currentTimeMillis() / 1000)
                .isBetween(50L, 61L);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 60_000L);
        assertThat(redis.opsForValue().get(counter)).isEqualTo("7");
        assertThat(redis.opsForValue().get(counter + ":block")).isEqualTo("1");
        assertThat(redis.opsForValue().increment(counter)).isEqualTo(8);
    }

    @Test
    @DisplayName("Lua 脚本：固定 now=268382706.34399077ms、limit=1 首个请求放行；读取 fastapi 格式 TAT 后判定正确并写回 3 位小数")
    @SuppressWarnings("rawtypes")
    void luaScriptWithFixedNow() {
        RedisScript<List> script =
                RedisScript.of("local now = tonumber(ARGV[3])\n" + RateLimiter.SCRIPT_BODY, List.class);
        String fresh = "throttle:it-" + newIp() + ":default:gcra";
        String interval = Long.toString(Gcra.interval(MINUTE, 1));

        assertThat(redis.execute(script, List.of(fresh), Long.toString(MINUTE), interval, Long.toString(FIXED_NOW)))
                .isEqualTo(List.of(1L, 0L, MINUTE, 0L));
        assertThat(redis.opsForValue().get(fresh)).isEqualTo("268442706.344");
        assertThat(redis.getExpire(fresh, TimeUnit.MILLISECONDS)).isBetween(59_000L, 60_000L);
        assertThat(redis.execute(script, List.of(fresh), Long.toString(MINUTE), interval, Long.toString(FIXED_NOW)))
                .isEqualTo(List.of(0L, 0L, MINUTE, MINUTE));

        String fastapi = "throttle:it-fastapi-" + newIp() + ":default:gcra";
        redis.opsForValue().set(fastapi, "268382706.34399077", 60, TimeUnit.SECONDS);
        String half = Long.toString(Gcra.interval(MINUTE, 2));
        assertThat(redis.execute(
                        script, List.of(fastapi), Long.toString(MINUTE), half, Long.toString(FIXED_NOW - 30_000_001L)))
                .isEqualTo(List.of(0L, 0L, 30_000_001L, 1L));
        assertThat(redis.execute(
                        script, List.of(fastapi), Long.toString(MINUTE), half, Long.toString(FIXED_NOW - 30_000_000L)))
                .isEqualTo(List.of(1L, 0L, MINUTE, 0L));
        assertThat(redis.opsForValue().get(fastapi)).isEqualTo("268412706.344");
    }

    @Test
    @DisplayName("Swagger 文档路由不计数")
    void docsAreNotThrottled() throws Exception {
        MockHttpServletResponse response = get("/docs-json", newIp());
        assertThat(response.getStatus()).isEqualTo(200);
        assertNoRateHeaders(response);
    }
}
