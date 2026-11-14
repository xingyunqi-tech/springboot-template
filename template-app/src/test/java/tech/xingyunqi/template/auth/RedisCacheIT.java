package tech.xingyunqi.template.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = "REDIS_ENABLED=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RedisCacheIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @Test
    @DisplayName("鉴权缓存以 nestjs Keyv 格式写入 Redis，logout 后删除")
    void sessionCacheUsesKeyvFormat() throws Exception {
        AuthApi api = new AuthApi(mvc);
        String token = api.loginData(AuthApi.JOHN).path("token").asString();
        int sessionId = AuthApi.payload(token).path("sessionId").asInt();
        String key = "cache::jwt:session:" + sessionId;
        redis.delete(key);

        assertThat(api.call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(200);
        JsonNode stored = AuthApi.JSON.readTree(redis.opsForValue().get(key));
        assertThat(stored.path("value").toString()).isEqualTo("{\"statusId\":1,\"roleId\":2}");
        assertThat(stored.path("expires").asLong()).isGreaterThan(System.currentTimeMillis());
        assertThat(redis.getExpire(key)).isBetween(1L, 30L);

        assertThat(api.call("POST", "/api/v1/auth/logout", null, token).status())
                .isEqualTo(200);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(api.call("GET", "/api/v1/auth/me", null, token).status()).isEqualTo(401);
    }
}
