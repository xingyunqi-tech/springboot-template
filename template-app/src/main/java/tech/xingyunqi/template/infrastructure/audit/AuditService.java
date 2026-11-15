package tech.xingyunqi.template.infrastructure.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.xingyunqi.template.common.web.config.AppProperties;
import tech.xingyunqi.template.common.web.filter.RequestContextFilter;
import tech.xingyunqi.template.common.web.http.ClientInfo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** nestjs {@code AuditService.log}：脱敏后写 {@code audit_logs}，带请求的 IP、User-Agent 与 Request ID。 */
@Service
public class AuditService {

    static final Set<String> SENSITIVE_KEY_PARTS =
            Set.of("password", "secret", "token", "apikey", "privatekey", "sessionkey", "accesskey");
    static final Set<String> SENSITIVE_KEYS = Set.of("hash");
    static final String REDACTED = "[REDACTED]";
    static final int MAX_DEPTH = 10;

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogMapper mapper;
    private final JsonMapper json;
    private final AppProperties app;

    public AuditService(AuditLogMapper mapper, JsonMapper json, AppProperties app) {
        this.mapper = mapper;
        this.json = json;
        this.app = app;
    }

    /** 一条审计记录；{@code oldValue} / {@code newValue} 为对象或数组，null 时写入 SQL NULL。 */
    public record Entry(
            AuditAction action,
            String entityType,
            String entityId,
            @Nullable Object userId,
            @Nullable JsonNode oldValue,
            @Nullable JsonNode newValue) {}

    public void log(Entry entry, @Nullable HttpServletRequest request) {
        mapper.insert(new AuditLogMapper.NewAuditLog(
                entry.userId() == null ? null : String.valueOf(entry.userId()),
                entry.action().name(),
                entry.entityType(),
                entry.entityId(),
                serialize(sanitize(entry.oldValue())),
                serialize(sanitize(entry.newValue())),
                request == null ? null : clientIp(request, app.trustProxy()),
                request == null ? null : request.getHeader("User-Agent"),
                request == null ? null : RequestContextFilter.requestId(request)));
        LOG.debug(
                "Audit log created: {} on {}:{} by user {}",
                entry.action(),
                entry.entityType(),
                entry.entityId(),
                entry.userId() == null ? "anonymous" : entry.userId());
    }

    private @Nullable String serialize(@Nullable JsonNode value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(lower) || SENSITIVE_KEY_PARTS.stream().anyMatch(lower::contains);
    }

    /**
     * 敏感键替换为 {@code [REDACTED]}，超过 {@value #MAX_DEPTH} 层为 {@code {"__truncated": true}}；顶层数组按下标转成对象（与
     * {@code Object.entries} 一致）。
     */
    static @Nullable ObjectNode sanitize(@Nullable JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? null : sanitize(value, 0);
    }

    private static ObjectNode sanitize(JsonNode value, int depth) {
        ObjectNode sanitized = JsonNodeFactory.instance.objectNode();
        if (depth > MAX_DEPTH) {
            return sanitized.put("__truncated", true);
        }
        if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                sanitized.set(Integer.toString(i), sanitizeValue(value.get(i), depth));
            }
            return sanitized;
        }
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            sanitized.set(
                    field.getKey(),
                    isSensitiveKey(field.getKey())
                            ? sanitized.stringNode(REDACTED)
                            : sanitizeValue(field.getValue(), depth));
        }
        return sanitized;
    }

    private static JsonNode sanitizeValue(JsonNode value, int depth) {
        if (value.isArray()) {
            ArrayNode items = JsonNodeFactory.instance.arrayNode();
            value.values().forEach(item -> items.add(sanitizeValue(item, depth + 1)));
            return items;
        }
        if (value.isObject()) {
            return sanitize(value, depth + 1);
        }
        return value;
    }

    /** {@code X-Forwarded-For} 的第一个地址，否则为 Express {@code req.ip}。 */
    static @Nullable String clientIp(HttpServletRequest request, int trustProxy) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",", -1)[0].strip();
        }
        return ClientInfo.clientIp(request, trustProxy);
    }
}
