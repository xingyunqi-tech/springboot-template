package tech.xingyunqi.template.common.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JwtTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-11-13T00:00:00.700Z");

    final Jwt jwt = new Jwt(JSON, Clock.fixed(NOW, ZoneOffset.UTC));

    static String token(String header, String payload, String algorithm, String secret) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return input + "." + encoder.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("签发的载荷保持键顺序并追加 iat、exp（秒）")
    void signsPayloadWithIatAndExp() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", 1);
        payload.put("sessionId", 2);
        JsonNode claims = jwt.verify(jwt.sign(payload, "secret", "15m"), "secret");

        assertThat(new ArrayList<>(claims.propertyNames())).containsExactly("id", "sessionId", "iat", "exp");
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 900);
    }

    @Test
    @DisplayName("exp 不大于当前秒时为过期")
    void expiredToken() {
        String token = jwt.sign(Map.of("id", 1), "secret", "0s");
        assertThatThrownBy(() -> jwt.verify(token, "secret")).isInstanceOf(Jwt.TokenExpiredException.class);
    }

    @Test
    @DisplayName("密钥不同、签名末尾追加填充或改动末字符都判为无效")
    void rejectsAlteredSignature() {
        String token = jwt.sign(Map.of("id", 1), "secret", "1h");
        char last = token.charAt(token.length() - 1);
        String flipped = token.substring(0, token.length() - 1) + (char) (last ^ 1);

        assertThatThrownBy(() -> jwt.verify(token, "other")).isInstanceOf(Jwt.InvalidTokenException.class);
        assertThatThrownBy(() -> jwt.verify(token + "=", "secret")).isInstanceOf(Jwt.InvalidTokenException.class);
        assertThatThrownBy(() -> jwt.verify(flipped, "secret")).isInstanceOf(Jwt.InvalidTokenException.class);
    }

    @Test
    @DisplayName("接受 HS384 / HS512，拒绝 none 与其它算法")
    void algorithms() throws Exception {
        String payload = "{\"id\":1}";
        assertThat(jwt.verify(token("{\"alg\":\"HS512\"}", payload, "HmacSHA512", "s"), "s")
                        .path("id")
                        .asInt())
                .isEqualTo(1);
        assertThat(jwt.verify(token("{\"alg\":\"HS384\"}", payload, "HmacSHA384", "s"), "s")
                        .path("id")
                        .asInt())
                .isEqualTo(1);
        assertThatThrownBy(() -> jwt.verify(token("{\"alg\":\"none\"}", payload, "HmacSHA256", "s"), "s"))
                .isInstanceOf(Jwt.InvalidTokenException.class);
    }

    @Test
    @DisplayName("nbf 晚于当前时间或不是数字时无效")
    void notBefore() throws Exception {
        String header = "{\"alg\":\"HS256\"}";
        long now = NOW.getEpochSecond();
        assertThatThrownBy(() -> jwt.verify(token(header, "{\"nbf\":" + (now + 10) + "}", "HmacSHA256", "s"), "s"))
                .isInstanceOf(Jwt.InvalidTokenException.class);
        assertThatThrownBy(() -> jwt.verify(token(header, "{\"nbf\":\"x\"}", "HmacSHA256", "s"), "s"))
                .isInstanceOf(Jwt.InvalidTokenException.class);
        assertThat(jwt.verify(token(header, "{\"nbf\":" + now + "}", "HmacSHA256", "s"), "s"))
                .isNotNull();
    }

    @Test
    @DisplayName("格式错误的令牌无效")
    void malformed() {
        for (String token : new String[] {"", "a.b", "a.b.c.d", "!!.@@.##"}) {
            assertThatThrownBy(() -> jwt.verify(token, "secret")).isInstanceOf(Jwt.InvalidTokenException.class);
        }
    }
}
