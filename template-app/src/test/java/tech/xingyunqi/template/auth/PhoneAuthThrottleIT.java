package tech.xingyunqi.template.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tech.xingyunqi.template.support.TestcontainersConfiguration;

@SpringBootTest(properties = {"app.throttle.enabled=true", "app.trust-proxy=1", "app.sms.mock-mode=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PhoneAuthThrottleIT {

    static final AtomicInteger CLIENTS = new AtomicInteger(new SecureRandom().nextInt(1 << 22));

    @Autowired
    MockMvc mvc;

    static String newIp() {
        int n = CLIENTS.incrementAndGet() & 0xFFFFFF;
        return "10." + (n >> 16) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }

    MockHttpServletResponse post(String path, String ip) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("X-Forwarded-For", ip)
                        .contentType("application/json")
                        .content("{}"))
                .andReturn()
                .getResponse();
    }

    @ParameterizedTest
    @CsvSource({
        "/api/v1/auth/phone/send-code, 3, 60",
        "/api/v1/auth/phone/login, 5, 60",
        "/api/v1/auth/phone/sms-login, 5, 60",
        "/api/v1/auth/phone/register, 3, 60",
        "/api/v1/auth/phone/reset-password, 5, 300",
        "/api/v1/auth/wechat/login, 10, 60",
        "/api/v1/auth/wechat/phone-login, 10, 60",
        "/api/v1/auth/alipay/login, 10, 60"
    })
    @DisplayName("各登录接口的限流值与 nestjs 一致")
    void routeLimits(String path, int limit, int ttlSeconds) throws Exception {
        MockHttpServletResponse first = post(path, newIp());
        assertThat(first.getStatus()).isEqualTo(422);
        assertThat(first.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(limit));
        assertThat(Integer.parseInt(first.getHeader("X-RateLimit-Reset"))).isBetween(1, ttlSeconds);
    }

    @Test
    @DisplayName("send-code 同一客户端第 4 次请求为 429")
    void sendCodeThrottled() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/v1/auth/phone/send-code", ip).getStatus()).isEqualTo(422);
        }
        MockHttpServletResponse throttled = post("/api/v1/auth/phone/send-code", ip);
        assertThat(throttled.getStatus()).isEqualTo(429);
        assertThat(throttled.getHeader("Retry-After")).matches("\\d+");
    }
}
