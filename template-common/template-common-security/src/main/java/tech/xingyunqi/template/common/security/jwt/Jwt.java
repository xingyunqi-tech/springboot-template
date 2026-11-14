package tech.xingyunqi.template.common.security.jwt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 与 jsonwebtoken 兼容的 HMAC JWT：签发 HS256，{@code iat} 为秒，{@code exp = iat + floor(ms(expiresIn) / 1000)}。 */
public final class Jwt {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Map<String, String> ALGORITHMS =
            Map.of("HS256", "HmacSHA256", "HS384", "HmacSHA384", "HS512", "HmacSHA512");
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final JsonMapper mapper;
    private final Clock clock;

    public Jwt(JsonMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public String sign(Map<String, ?> payload, String secret, String expiresIn) {
        long issuedAt = clock.millis() / 1000;
        Map<String, Object> claims = new LinkedHashMap<>(payload);
        claims.put("iat", issuedAt);
        claims.put("exp", (long) Math.floor(issuedAt + Ms.parse(expiresIn) / 1000));
        String input = encode(HEADER.getBytes(StandardCharsets.UTF_8)) + "." + encode(mapper.writeValueAsBytes(claims));
        return input + "." + encode(hmac("HmacSHA256", secret, input));
    }

    public ObjectNode verify(String token, String secret) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException();
        }
        JsonNode header = decodeJson(parts[0]);
        String algorithm = ALGORITHMS.get(header.path("alg").asString(""));
        if (algorithm == null) {
            throw new InvalidTokenException();
        }
        byte[] expected =
                encode(hmac(algorithm, secret, parts[0] + "." + parts[1])).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.US_ASCII))) {
            throw new InvalidTokenException();
        }
        if (!(decodeJson(parts[1]) instanceof ObjectNode claims)) {
            throw new InvalidTokenException();
        }
        long now = clock.millis() / 1000;
        JsonNode nbf = claims.get("nbf");
        if (nbf != null) {
            if (!nbf.isNumber() || now < nbf.asDouble()) {
                throw new InvalidTokenException();
            }
        }
        JsonNode exp = claims.get("exp");
        if (exp != null) {
            if (!exp.isNumber()) {
                throw new InvalidTokenException();
            }
            if (now >= exp.asDouble()) {
                throw new TokenExpiredException();
            }
        }
        return claims;
    }

    private JsonNode decodeJson(String part) {
        try {
            return mapper.readTree(DECODER.decode(part));
        } catch (IllegalArgumentException | JacksonException e) {
            throw new InvalidTokenException();
        }
    }

    private static String encode(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    private static byte[] hmac(String algorithm, String secret, String input) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 签名、格式或算法无效。 */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException() {
            super(null, null, false, false);
        }
    }

    /** 已过期（签名有效）。 */
    public static final class TokenExpiredException extends InvalidTokenException {}
}
