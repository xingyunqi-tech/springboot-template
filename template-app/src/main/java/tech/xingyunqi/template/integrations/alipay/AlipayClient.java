package tech.xingyunqi.template.integrations.alipay;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.integrations.http.OutboundHttp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 支付宝小程序登录：{@code alipay.system.oauth.token} 用授权码换取 open_id。 */
@Component
public class AlipayClient {

    static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Logger LOG = LoggerFactory.getLogger(AlipayClient.class);

    private final AlipayProperties properties;
    private final JsonMapper mapper;
    private final Clock clock;
    private final OutboundHttp http = new OutboundHttp(TIMEOUT);

    public AlipayClient(AlipayProperties properties, JsonMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 返回 open_id；授权码无效为 {@code invalidAlipayAuthCode}，未配置或请求失败为 {@code alipay} 字段错误。 */
    public String exchangeAuthCode(String authCode) {
        String appId = properties.appId();
        String privateKey = properties.privateKey();
        if (appId == null || appId.isEmpty() || privateKey == null || privateKey.isEmpty()) {
            LOG.error("Alipay credentials not configured");
            throw HttpStatusException.validationError("alipay", "notConfigured");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", appId);
        params.put("method", "alipay.system.oauth.token");
        params.put("format", "JSON");
        params.put("charset", properties.charsetOrDefault());
        params.put("sign_type", properties.signTypeOrDefault());
        params.put("timestamp", TIMESTAMP.format(clock.instant().atOffset(BEIJING)));
        params.put("version", "1.0");
        params.put("grant_type", "authorization_code");
        params.put("code", authCode);
        params.put("sign", sign(params, privateKey, properties.signTypeOrDefault()));

        JsonNode response;
        try {
            response = send(params);
        } catch (OutboundHttp.TransportException | AlipayRequestException e) {
            LOG.error("Alipay oauth token request failed: {}", e.getMessage());
            throw HttpStatusException.validationError("alipay", "requestFailed");
        }

        JsonNode error = response.get("error_response");
        if (error != null && error.isObject()) {
            String subCode = text(error, "sub_code");
            LOG.error("Alipay oauth token failed: {} {}", text(error, "code"), subCode == null ? "" : subCode);
            if (subCode != null && subCode.startsWith("isv.code-")) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", 422);
                body.put("error", "invalidAlipayAuthCode");
                body.put("errors", Map.of("authCode", "invalidCode"));
                throw HttpStatusException.unprocessable(body);
            }
            throw HttpStatusException.validationError("alipay", "requestFailed");
        }
        String openId = text(response.path("alipay_system_oauth_token_response"), "open_id");
        if (openId == null || openId.isEmpty()) {
            LOG.error("Alipay oauth token response has no open_id, check that OpenID is enabled for the app");
            throw HttpStatusException.validationError("alipay", "requestFailed");
        }
        return openId;
    }

    private JsonNode send(Map<String, String> params) throws OutboundHttp.TransportException, AlipayRequestException {
        String query = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        OutboundHttp.Response response =
                http.send(OutboundHttp.request(URI.create(properties.gatewayOrDefault() + "?" + query))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .GET());
        if (!response.ok()) {
            throw new AlipayRequestException("Alipay request failed: HTTP " + response.status());
        }
        JsonNode data;
        try {
            data = mapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new AlipayRequestException("Alipay response is not JSON");
        }
        if (data.isMissingNode()) {
            throw new AlipayRequestException("Alipay response is not JSON");
        }
        return data;
    }

    static String signString(Map<String, String> params) {
        return new TreeMap<>(params)
                .entrySet().stream()
                        .filter(e -> !e.getValue().isEmpty())
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("&"));
    }

    static String sign(Map<String, String> params, String privateKey, String signType) {
        try {
            Signature signature = Signature.getInstance("RSA2".equals(signType) ? "SHA256withRSA" : "SHA1withRSA");
            signature.initSign(parsePrivateKey(privateKey));
            signature.update(signString(params).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("ALIPAY_PRIVATE_KEY is not a valid PKCS#8 RSA private key", e);
        }
    }

    static PrivateKey parsePrivateKey(String privateKey) throws GeneralSecurityException {
        String body = privateKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isString() ? value.asString() : value.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static final class AlipayRequestException extends Exception {

        AlipayRequestException(String message) {
            super(message, null, false, false);
        }
    }
}
