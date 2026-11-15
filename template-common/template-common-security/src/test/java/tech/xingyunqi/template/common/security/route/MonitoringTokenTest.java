package tech.xingyunqi.template.common.security.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.security.jwt.Jwt;
import tech.xingyunqi.template.common.web.config.MetricsProperties;
import tech.xingyunqi.template.common.web.route.MonitoringToken;
import tools.jackson.databind.json.JsonMapper;

class MonitoringTokenTest {

    static final String TOKEN = "monitoring-token-0123456789";

    static class Monitored {
        @MonitoringToken
        public void handler() {}
    }

    static HandlerMethod handler() throws Exception {
        Monitored bean = new Monitored();
        return new HandlerMethod(bean, Monitored.class.getMethod("handler"));
    }

    static RouteAuthInterceptor interceptor(@Nullable String token, boolean production) {
        AuthProperties auth = new AuthProperties("a", "15m", "b", "3650d", "c", "30m", "d", "1d", "argon2id", 1);
        return new RouteAuthInterceptor(
                new Jwt(JsonMapper.builder().build(), Clock.systemUTC()),
                auth,
                mock(AccessTokenValidator.class),
                mock(PermissionResolver.class),
                new MetricsProperties(null, null, token),
                production);
    }

    static boolean allowed(RouteAuthInterceptor interceptor, @Nullable String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/metrics");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        try {
            return interceptor.preHandle(request, new MockHttpServletResponse(), handler());
        } catch (HttpStatusException e) {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.response()).hasToString("{statusCode=403, message=Forbidden resource, error=Forbidden}");
            return false;
        }
    }

    @Test
    @DisplayName("@MonitoringToken 是独立的鉴权方式")
    void declaresMonitoringMode() throws Exception {
        assertThat(RouteAuth.of(handler()).mode()).isEqualTo(RouteAuth.Mode.MONITORING);
    }

    @Test
    @DisplayName("未配置令牌时非生产放行、生产拒绝")
    void withoutTokenOnlyNonProductionPasses() throws Exception {
        assertThat(allowed(interceptor(null, false), null)).isTrue();
        assertThat(allowed(interceptor("", false), null)).isTrue();
        assertThat(allowed(interceptor(null, true), "Bearer " + TOKEN)).isFalse();
    }

    @Test
    @DisplayName("配置了令牌时生产与非生产都要求 Bearer 令牌")
    void tokenRequiredWhenConfigured() throws Exception {
        for (boolean production : new boolean[] {false, true}) {
            RouteAuthInterceptor interceptor = interceptor(TOKEN, production);
            assertThat(allowed(interceptor, "Bearer " + TOKEN)).isTrue();
            assertThat(allowed(interceptor, null)).isFalse();
            assertThat(allowed(interceptor, "Bearer " + TOKEN + "x")).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Bearer secret|true",
                "'  BEARER   secret  '|true",
                "Bearer secret extra|true",
                "Bearer secre|false",
                "Basic secret|false",
                "Bearer|false",
                "''|false"
            })
    @DisplayName("令牌比较：scheme 不区分大小写，按空白切分取第二段")
    void tokenMatching(String header, boolean expected) {
        assertThat(RouteAuthInterceptor.monitoringTokenMatches(header, "secret"))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("METRICS_TOKEN 少于 16 个字符时启动失败")
    void shortTokenIsRejected() {
        assertThatThrownBy(() -> new MetricsProperties(null, null, "short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16");
    }
}
