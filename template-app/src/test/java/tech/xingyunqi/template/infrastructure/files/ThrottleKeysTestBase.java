package tech.xingyunqi.template.infrastructure.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tech.xingyunqi.template.common.redis.throttle.RateLimiter;

/** S3 驱动下 files 路由的限流键与 nestjs handler 名一致（local 驱动见 {@code ThrottleIT}）。 */
abstract class ThrottleKeysTestBase {

    static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    abstract Map<String, String> expectedHandlers();

    @Test
    @DisplayName("files 路由使用 nestjs 的 handler 键")
    void filesRoutesUseNestjsHandlerKeys() throws Exception {
        for (Map.Entry<String, String> route : expectedHandlers().entrySet()) {
            String ip = "10.252." + RANDOM.nextInt(250) + "." + RANDOM.nextInt(250);
            String[] parts = route.getKey().split(" ", 2);
            MockHttpServletResponse response = mvc.perform(
                            MockMvcRequestBuilders.request(HttpMethod.valueOf(parts[0]), parts[1])
                                    .header("X-Forwarded-For", ip))
                    .andReturn()
                    .getResponse();
            assertThat(response.getHeader("X-RateLimit-Limit"))
                    .as(route.getKey())
                    .isNotNull();
            assertThat(redis.hasKey(RateLimiter.key(route.getValue(), "ip:" + ip)))
                    .as(route.getKey())
                    .isTrue();
        }
    }
}
