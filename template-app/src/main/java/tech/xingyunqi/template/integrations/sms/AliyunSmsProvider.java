package tech.xingyunqi.template.integrations.sms;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.integrations.http.OutboundHttp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 阿里云 Dysmsapi SendSms（2017-05-25，ACS3-HMAC-SHA256 签名）；{@code SMS_MOCK_MODE=true} 时只打日志并返回成功。 */
@Component
public class AliyunSmsProvider {

    /** 发送结果；失败时 {@code code} 为阿里云错误码或 {@code CLIENT_NOT_INITIALIZED} / {@code CONFIG_ERROR} / {@code SEND_ERROR}。 */
    public record SendSmsResult(
            boolean success,
            @Nullable String code,
            @Nullable String message,
            @Nullable String requestId,
            @Nullable String bizId) {

        static SendSmsResult failure(String code, @Nullable String message) {
            return new SendSmsResult(false, code, message, null, null);
        }
    }

    static final String API_VERSION = "2017-05-25";
    static final String ACTION = "SendSms";
    static final String ALGORITHM = "ACS3-HMAC-SHA256";
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter ACS_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final Logger LOG = LoggerFactory.getLogger(AliyunSmsProvider.class);

    private final SmsProperties properties;
    private final JsonMapper mapper;
    private final Clock clock;
    private final OutboundHttp http = new OutboundHttp(TIMEOUT);
    private final boolean clientInitialized;

    public AliyunSmsProvider(SmsProperties properties, JsonMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        if (properties.mock()) {
            LOG.warn("SMS is running in mock mode - messages will not be sent");
            clientInitialized = false;
        } else if (!properties.hasCredentials()) {
            LOG.warn("SMS credentials not configured - SMS sending will be disabled");
            clientInitialized = false;
        } else {
            clientInitialized = true;
        }
    }

    public SendSmsResult sendSms(String phone, Map<String, String> templateParam) {
        String params = mapper.writeValueAsString(templateParam);
        if (properties.mock()) {
            LOG.info("[MOCK SMS] To: {}, Params: {}", PhoneMask.mask(phone), params);
            return new SendSmsResult(true, "OK", "Mock SMS sent successfully", "mock-request-id", "mock-biz-id");
        }
        if (!clientInitialized) {
            LOG.error("SMS client not initialized");
            return SendSmsResult.failure("CLIENT_NOT_INITIALIZED", "SMS client not initialized");
        }
        String signName = properties.signName();
        String templateCode = properties.templateCode();
        if (signName == null || signName.isEmpty() || templateCode == null || templateCode.isEmpty()) {
            LOG.error("SMS sign name or template code not configured");
            return SendSmsResult.failure("CONFIG_ERROR", "SMS configuration incomplete");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("PhoneNumbers", phone);
        query.put("SignName", signName);
        query.put("TemplateCode", templateCode);
        query.put("TemplateParam", params);
        JsonNode body;
        try {
            body = call(query);
        } catch (OutboundHttp.TransportException | SmsApiException e) {
            LOG.error("SMS send error: {}", e.getMessage());
            return SendSmsResult.failure("SEND_ERROR", e.getMessage());
        }
        String code = text(body, "Code");
        if ("OK".equals(code)) {
            LOG.info("SMS sent successfully to {}, BizId: {}", PhoneMask.mask(phone), text(body, "BizId"));
            return new SendSmsResult(true, code, text(body, "Message"), text(body, "RequestId"), text(body, "BizId"));
        }
        LOG.error("SMS send failed: {} - {}", code, text(body, "Message"));
        return new SendSmsResult(false, code, text(body, "Message"), text(body, "RequestId"), null);
    }

    private JsonNode call(Map<String, String> query) throws OutboundHttp.TransportException, SmsApiException {
        URI base = baseUri(properties.endpointOrDefault());
        String canonicalQuery = canonicalQuery(query);
        Map<String, String> headers = signedHeaders(
                base.getRawAuthority(),
                canonicalQuery,
                properties.accessKeyId(),
                properties.accessKeySecret(),
                ACS_DATE.format(Instant.now(clock).atOffset(ZoneOffset.UTC)),
                UUID.randomUUID().toString().replace("-", ""));
        HttpRequest.Builder request = OutboundHttp.request(URI.create(base + "/?" + canonicalQuery))
                .POST(HttpRequest.BodyPublishers.noBody());
        headers.forEach((name, value) -> {
            if (!"host".equals(name)) {
                request.header(name, value);
            }
        });
        OutboundHttp.Response response = http.send(request);
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new SmsApiException("SMS response body is not JSON (HTTP " + response.status() + ")");
        }
        if (!body.isObject()) {
            throw new SmsApiException("SMS response body is empty");
        }
        if (!response.ok()) {
            throw new SmsApiException(text(body, "Code") + ": " + text(body, "Message"));
        }
        return body;
    }

    static URI baseUri(String endpoint) {
        String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return URI.create(trimmed.contains("://") ? trimmed : "https://" + trimmed);
    }

    /** 带签名的请求头（请求体为空）；键为小写。 */
    static Map<String, String> signedHeaders(
            String host,
            String canonicalQuery,
            @Nullable String accessKeyId,
            @Nullable String accessKeySecret,
            String date,
            String nonce) {
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-acs-action", ACTION);
        headers.put("x-acs-content-sha256", sha256Hex(new byte[0]));
        headers.put("x-acs-date", date);
        headers.put("x-acs-signature-nonce", nonce);
        headers.put("x-acs-version", API_VERSION);
        String canonicalHeaders = headers.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().strip() + "\n")
                .collect(Collectors.joining());
        String signedNames = String.join(";", headers.keySet());
        String canonicalRequest = String.join(
                "\n", "POST", "/", canonicalQuery, canonicalHeaders, signedNames, headers.get("x-acs-content-sha256"));
        String stringToSign = ALGORITHM + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = hmacSha256Hex(accessKeySecret == null ? "" : accessKeySecret, stringToSign);
        Map<String, String> result = new LinkedHashMap<>(headers);
        result.put("accept", "application/json");
        result.put(
                "authorization",
                ALGORITHM + " Credential=" + accessKeyId + ",SignedHeaders=" + signedNames + ",Signature=" + signature);
        return result;
    }

    static String canonicalQuery(Map<String, String> query) {
        return new TreeMap<>(query)
                .entrySet().stream()
                        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                        .collect(Collectors.joining("&"));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @Nullable String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.isString() ? value.asString() : value.toString();
    }

    static final class SmsApiException extends Exception {

        SmsApiException(String message) {
            super(message, null, false, false);
        }
    }
}
