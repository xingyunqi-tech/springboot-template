package tech.xingyunqi.template.integrations.wechat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.redis.Cache;
import tech.xingyunqi.template.integrations.http.OutboundHttp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

/** 微信服务端接口：小程序 jscode2session、APP OAuth、小程序 access_token（stable_token，带缓存）与手机号。 */
@Component
public class WechatClient {

    /** 微信返回的用户标识；APP 登录额外带昵称、头像与性别。 */
    public record UserInfo(
            @Nullable String openId,
            @Nullable String unionId,
            @Nullable String sessionKey,
            @Nullable String nickname,
            @Nullable String avatar,
            @Nullable Integer gender) {}

    /** 不带区号的手机号与区号。 */
    public record PhoneInfo(String purePhoneNumber, String countryCode) {}

    /** 微信返回非 0 errcode。 */
    public static final class ApiException extends RuntimeException {

        private final int errcode;
        private final String errmsg;

        public ApiException(int errcode, String errmsg) {
            super("WeChat API error " + errcode + ": " + errmsg, null, false, false);
            this.errcode = errcode;
            this.errmsg = errmsg;
        }

        public int errcode() {
            return errcode;
        }

        public String errmsg() {
            return errmsg;
        }
    }

    /** 连接失败、超时或响应不是 JSON；消息不含 URL query。 */
    public static final class RequestException extends RuntimeException {

        RequestException(String message) {
            super(message, null, false, false);
        }
    }

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final String MINI_APP_ACCESS_TOKEN_CACHE_KEY = "wechat:mini_app:access_token";
    static final Set<Integer> ACCESS_TOKEN_INVALID_ERROR_CODES = Set.of(40001, 40014, 42001);
    static final Set<Integer> PHONE_CODE_INVALID_ERROR_CODES = Set.of(40029, 40163);
    private static final Logger LOG = LoggerFactory.getLogger(WechatClient.class);

    private final WechatProperties properties;
    private final Cache cache;
    private final JsonMapper mapper;
    private final OutboundHttp http = new OutboundHttp(TIMEOUT);

    public WechatClient(WechatProperties properties, Cache cache, JsonMapper mapper) {
        this.properties = properties;
        this.cache = cache;
        this.mapper = mapper;
    }

    public UserInfo miniAppLogin(String code) {
        String label = "WeChat Mini App";
        String appId = properties.miniAppId();
        String appSecret = properties.miniAppSecret();
        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.error("{} credentials not configured", label);
            throw wechatError("wechat", "notConfigured", null);
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appid", appId);
        params.put("secret", appSecret);
        params.put("js_code", code);
        params.put("grant_type", "authorization_code");
        JsonNode data;
        try {
            data = fetch("/sns/jscode2session", params, null, label + " login");
        } catch (RequestException e) {
            LOG.error("{} login request failed: {}", label, e.getMessage());
            throw requestFailed();
        }
        if (truthy(data.get("errcode"))) {
            LOG.error("{} login failed: {} - {}", label, data.get("errcode"), text(data, "errmsg"));
            throw wechatError("code", "invalidCode", data.get("errmsg"));
        }
        LOG.info("{} login successful for openid: {}", label, text(data, "openid"));
        return new UserInfo(text(data, "openid"), text(data, "unionid"), text(data, "session_key"), null, null, null);
    }

    public UserInfo appLogin(String code) {
        String appId = properties.appId();
        String appSecret = properties.appSecret();
        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.error("WeChat App credentials not configured");
            throw wechatError("wechat", "notConfigured", null);
        }
        Map<String, String> tokenParams = new LinkedHashMap<>();
        tokenParams.put("appid", appId);
        tokenParams.put("secret", appSecret);
        tokenParams.put("code", code);
        tokenParams.put("grant_type", "authorization_code");
        JsonNode token;
        JsonNode info;
        try {
            token = fetch("/sns/oauth2/access_token", tokenParams, null, "WeChat App token");
            if (truthy(token.get("errcode"))) {
                LOG.error("WeChat App token exchange failed: {} - {}", token.get("errcode"), text(token, "errmsg"));
                throw wechatError("code", "invalidCode", token.get("errmsg"));
            }
            Map<String, String> infoParams = new LinkedHashMap<>();
            infoParams.put("access_token", String.valueOf(text(token, "access_token")));
            infoParams.put("openid", String.valueOf(text(token, "openid")));
            info = fetch("/sns/userinfo", infoParams, null, "WeChat App user info");
        } catch (RequestException e) {
            LOG.error("WeChat App login request failed: {}", e.getMessage());
            throw requestFailed();
        }
        if (truthy(info.get("errcode"))) {
            LOG.error("WeChat App user info failed: {} - {}", info.get("errcode"), text(info, "errmsg"));
            throw wechatError("wechat", "userInfoFailed", info.get("errmsg"));
        }
        LOG.info("WeChat App login successful for openid: {}", text(token, "openid"));
        String unionId = text(info, "unionid");
        JsonNode sex = info.get("sex");
        return new UserInfo(
                text(token, "openid"),
                unionId != null ? unionId : text(token, "unionid"),
                null,
                text(info, "nickname"),
                text(info, "headimgurl"),
                sex != null && sex.isIntegralNumber() ? sex.asInt() : null);
    }

    /** 小程序 access_token（stable_token 普通模式），缓存到剩余有效期前 5 分钟。 */
    public String getMiniAppAccessToken() {
        JsonNode cached = cache.get(MINI_APP_ACCESS_TOKEN_CACHE_KEY);
        if (cached != null && cached.isString() && !cached.asString().isEmpty()) {
            return cached.asString();
        }
        return fetchStableToken(false);
    }

    /**
     * 以小程序 access_token 调用微信服务端接口（JSON POST），token 失效时刷新并重试一次。
     *
     * @throws ApiException 微信返回非 0 errcode
     * @throws RequestException 连接失败、超时或响应不是 JSON
     * @throws HttpStatusException 小程序凭证未配置（{@code wechat: notConfigured}）或微信返回非 2xx（{@code wechat: requestFailed}）
     */
    public JsonNode callMiniAppApi(String path, Object body, String label) {
        String accessToken = getMiniAppAccessToken();
        JsonNode data = fetch(path, Map.of("access_token", accessToken), body, label);
        Integer errcode = errcode(data);
        if (errcode != null && ACCESS_TOKEN_INVALID_ERROR_CODES.contains(errcode)) {
            LOG.warn("{}: access_token invalid ({}), refreshing", label, errcode);
            data = fetch(path, Map.of("access_token", refreshMiniAppAccessToken(accessToken)), body, label);
            errcode = errcode(data);
        }
        if (errcode != null && errcode != 0) {
            throw new ApiException(errcode, errmsg(data));
        }
        return data;
    }

    /** 用手机号快速验证组件返回的 code 换取手机号；code 无效为 {@code invalidPhoneCode}，其它失败为 {@code wechat} 字段错误。 */
    public PhoneInfo getPhoneNumber(String code) {
        try {
            JsonNode data =
                    callMiniAppApi("/wxa/business/getuserphonenumber", Map.of("code", code), "Get phone number");
            JsonNode phoneInfo = data.path("phone_info");
            String pure = text(phoneInfo, "purePhoneNumber");
            if (pure == null || pure.isEmpty()) {
                throw new ApiException(-1, "phone_info missing");
            }
            return new PhoneInfo(pure, String.valueOf(text(phoneInfo, "countryCode")));
        } catch (HttpStatusException e) {
            throw e;
        } catch (ApiException e) {
            LOG.error("Get phone number failed: {}", e.getMessage());
            if (PHONE_CODE_INVALID_ERROR_CODES.contains(e.errcode())) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", 422);
                response.put("error", "invalidPhoneCode");
                response.put("errors", Map.of("phoneCode", "invalidCode"));
                throw HttpStatusException.unprocessable(response);
            }
            throw requestFailed();
        } catch (RuntimeException e) {
            LOG.error("Get phone number failed: {}", e.getMessage());
            throw requestFailed();
        }
    }

    /**
     * 发送小程序订阅消息，{@code data} 为模板字段 key 到取值的映射。
     *
     * @throws ApiException 微信返回非 0 errcode（如 43101 用户未订阅）
     */
    public void sendSubscribeMessage(
            String openId, String templateId, @Nullable String page, Map<String, String> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", openId);
        body.put("template_id", templateId);
        if (page != null) {
            body.put("page", page);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        data.forEach((key, value) -> values.put(key, Map.of("value", value)));
        body.put("data", values);
        body.put("miniprogram_state", properties.miniProgramStateOrDefault());
        body.put("lang", "zh_CN");
        callMiniAppApi("/cgi-bin/message/subscribe/send", body, "Send subscribe message");
    }

    private String refreshMiniAppAccessToken(String invalidToken) {
        cache.delete(MINI_APP_ACCESS_TOKEN_CACHE_KEY);
        String token = fetchStableToken(false);
        return token.equals(invalidToken) ? fetchStableToken(true) : token;
    }

    private String fetchStableToken(boolean forceRefresh) {
        String appId = properties.miniAppId();
        String appSecret = properties.miniAppSecret();
        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.error("WeChat Mini App credentials not configured");
            throw wechatError("wechat", "notConfigured", null);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grant_type", "client_credential");
        body.put("appid", appId);
        body.put("secret", appSecret);
        body.put("force_refresh", forceRefresh);
        JsonNode data = fetch("/cgi-bin/stable_token", Map.of(), body, "Get stable_token");
        Integer errcode = errcode(data);
        if (errcode != null && errcode != 0) {
            LOG.error("Get stable_token failed: {} - {}", errcode, text(data, "errmsg"));
            throw new ApiException(errcode, errmsg(data));
        }
        long expiresIn = data.path("expires_in").asLong(0);
        long ttlSeconds = expiresIn > 600 ? expiresIn - 300 : expiresIn / 2;
        String token = String.valueOf(text(data, "access_token"));
        if (ttlSeconds > 0) {
            cache.set(MINI_APP_ACCESS_TOKEN_CACHE_KEY, StringNode.valueOf(token), ttlSeconds * 1000);
        }
        return token;
    }

    /** GET（{@code body} 为 null）或 JSON POST；非 2xx 为 {@code wechat: requestFailed}。 */
    private JsonNode fetch(String path, Map<String, String> params, @Nullable Object body, String label) {
        String query = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        URI uri = URI.create(properties.apiBaseOrDefault() + path + (query.isEmpty() ? "" : "?" + query));
        HttpRequest.Builder request = OutboundHttp.request(uri);
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        OutboundHttp.Response response;
        try {
            response = http.send(request);
        } catch (OutboundHttp.TransportException e) {
            throw new RequestException(String.valueOf(e.getMessage()));
        }
        if (!response.ok()) {
            LOG.error("{} HTTP error: {}", label, response.status());
            throw requestFailed();
        }
        JsonNode data;
        try {
            data = mapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new RequestException(label + " response is not JSON");
        }
        if (data.isMissingNode()) {
            throw new RequestException(label + " response is not JSON");
        }
        return data;
    }

    private static HttpStatusException requestFailed() {
        return wechatError("wechat", "requestFailed", null);
    }

    private static HttpStatusException wechatError(String field, String error, @Nullable JsonNode wechatError) {
        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put(field, error);
        if (wechatError != null && !wechatError.isNull() && !wechatError.isMissingNode()) {
            errors.put("wechatError", wechatError);
        }
        return HttpStatusException.validationErrors(errors);
    }

    private static String errmsg(JsonNode data) {
        String errmsg = text(data, "errmsg");
        return errmsg == null ? "" : errmsg;
    }

    private static @Nullable Integer errcode(JsonNode data) {
        JsonNode value = data.get("errcode");
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    private static boolean truthy(@Nullable JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isNumber()) {
            return value.asDouble() != 0 && !Double.isNaN(value.asDouble());
        }
        if (value.isString()) {
            return !value.asString().isEmpty();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return true;
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
}
