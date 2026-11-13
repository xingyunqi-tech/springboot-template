package tech.xingyunqi.template.common.web.error;

import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import tech.xingyunqi.template.common.core.exception.BusinessException;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.core.exception.HttpStatusNames;
import tech.xingyunqi.template.common.core.exception.TooManyRequestsException;
import tech.xingyunqi.template.common.core.exception.UniqueConstraints;
import tech.xingyunqi.template.common.web.i18n.I18n;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/** nestjs {@code AllExceptionsFilter} 的移植：把异常转换为 {@code {code, message, data}}。 */
public final class ErrorTranslator {

    private static final String PG_UNIQUE_VIOLATION = "23505";
    private static final Map<Integer, String> STATUS_TO_I18N_KEY = Map.of(
            400, "badRequest",
            401, "unauthorized",
            403, "forbidden",
            404, "notFound",
            409, "conflict",
            410, "gone",
            422, "unprocessableEntity",
            429, "tooManyRequests",
            500, "internalServerError");
    private static final Set<String> DEFAULT_EXCEPTION_SUFFIXES = Set.of(
            "Bad Request Exception",
            "Unauthorized Exception",
            "Forbidden Exception",
            "Not Found Exception",
            "Unprocessable Entity Exception",
            "Internal Server Error Exception");

    private final I18n i18n;
    private final JsonMapper mapper;
    private final JsonNodeFactory nodes = JsonNodeFactory.instance;

    public ErrorTranslator(I18n i18n, JsonMapper mapper) {
        this.i18n = i18n;
        this.mapper = mapper;
    }

    public ErrorBody build(Throwable exception) {
        if (exception instanceof TooManyRequestsException tooMany) {
            String message = orElse(translate("tooManyRequests"), "Too many requests, please try again later");
            return new ErrorBody(429, text(message), null, tooMany.headers(), "Too Many Requests");
        }
        UniqueViolation violation = findUniqueViolation(exception);
        if (violation != null) {
            UniqueConstraints.Response mapped = UniqueConstraints.lookup(violation.constraint());
            String error = mapped.error();
            String message = error != null ? orElse(translate(error), error) : defaultMessage(mapped.status());
            Map<String, String> errors = mapped.errors();
            JsonNode data = errors == null ? null : translateErrors(mapper.valueToTree(errors));
            String constraint = violation.constraint();
            return new ErrorBody(
                    mapped.status(),
                    text(message),
                    data,
                    Map.of(),
                    "Unique constraint violation " + (constraint == null ? "unknown" : constraint));
        }
        if (exception instanceof HttpStatusException http) {
            return httpError(http);
        }
        return new ErrorBody(
                500,
                text(orElse(translate("internalServerError"), "Internal server error")),
                null,
                Map.of(),
                "Internal Server Error");
    }

    /** 状态码默认消息：i18n 表内的翻译，表外为 Nest {@code HttpStatus} 枚举名（不翻译）。 */
    public String defaultMessage(int status) {
        String key = STATUS_TO_I18N_KEY.get(status);
        if (key != null) {
            String translated = translate(key);
            if (translated != null) {
                return translated;
            }
        }
        return HttpStatusNames.enumName(status);
    }

    private ErrorBody httpError(HttpStatusException exception) {
        int status = exception.status();
        Object response = exception.response();
        JsonNode errors = null;
        JsonNode message;
        String error = exception.getClass().getSimpleName();
        if (response instanceof String raw) {
            message = text(orElse(translate(raw), raw));
        } else if (response instanceof Map<?, ?> map) {
            Object rawErrors = map.get("errors");
            errors = rawErrors == null ? null : toNode(rawErrors);
            Object rawError = map.get("error");
            String errorText = rawError instanceof String s ? s : null;
            String errorCode =
                    errorText != null && !errorText.isEmpty() && !HttpStatusNames.isDefaultDescription(errorText)
                            ? errorText
                            : null;
            message = translatedMessage(map.get("message"), status, errorCode);
            if (errorText != null && !errorText.isEmpty()) {
                error = errorText;
            }
            if (errors instanceof ObjectNode object && !(exception instanceof BusinessException)) {
                errors = translateErrors(object);
            }
        } else {
            message = text(defaultMessage(status));
        }
        return new ErrorBody(status, message, errors, exception.headers(), error);
    }

    private JsonNode translatedMessage(@Nullable Object raw, int status, @Nullable String errorCode) {
        if (raw == null || isEmpty(raw) || (raw instanceof String s && isDefaultMessage(s))) {
            if (errorCode != null) {
                return text(orElse(translate(errorCode), errorCode));
            }
            return text(defaultMessage(status));
        }
        if (raw instanceof String s) {
            return text(orElse(translate(s), s));
        }
        if (raw instanceof Iterable<?> items) {
            ArrayNode array = nodes.arrayNode();
            for (Object item : items) {
                array.add(item instanceof String s ? text(orElse(translate(s), s)) : toNode(item));
            }
            return array;
        }
        return toNode(raw);
    }

    private static boolean isEmpty(@Nullable Object raw) {
        return raw == null
                || (raw instanceof String s && s.isEmpty())
                || (raw instanceof Iterable<?> items && !items.iterator().hasNext());
    }

    private static boolean isDefaultMessage(String message) {
        if (HttpStatusNames.isDefaultDescription(message)) {
            return true;
        }
        for (String suffix : DEFAULT_EXCEPTION_SUFFIXES) {
            if (message.contains(suffix)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode translateErrors(JsonNode errors) {
        if (!(errors instanceof ObjectNode object)) {
            return errors;
        }
        ObjectNode translated = nodes.objectNode();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            JsonNode value = entry.getValue();
            if (value.isString()) {
                String raw = value.stringValue();
                translated.put(entry.getKey(), orElse(translate(raw), raw));
            } else {
                translated.set(entry.getKey(), value);
            }
        }
        return translated;
    }

    private JsonNode toNode(Object value) {
        return value instanceof JsonNode node ? node : mapper.valueToTree(value);
    }

    private @Nullable String translate(String key) {
        return i18n.translate("common." + key);
    }

    private static String orElse(@Nullable String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static StringNode text(String value) {
        return StringNode.valueOf(value);
    }

    record UniqueViolation(@Nullable String constraint) {}

    /** 沿 cause 与 {@code SQLException.getNextException} 链查找 PostgreSQL 23505，返回首个可得的约束名。 */
    static @Nullable UniqueViolation findUniqueViolation(Throwable exception) {
        boolean found = false;
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.Deque<Throwable> pending = new java.util.ArrayDeque<>();
        pending.add(exception);
        while (!pending.isEmpty()) {
            Throwable current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof SQLException sql) {
                if (PG_UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                    found = true;
                    String constraint = constraintName(sql);
                    if (constraint != null) {
                        return new UniqueViolation(constraint);
                    }
                }
                SQLException next = sql.getNextException();
                if (next != null) {
                    pending.add(next);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                pending.add(cause);
            }
        }
        return found ? new UniqueViolation(null) : null;
    }

    private static @Nullable String constraintName(SQLException exception) {
        try {
            if (exception instanceof PSQLException psql) {
                ServerErrorMessage message = psql.getServerErrorMessage();
                return message == null ? null : message.getConstraint();
            }
        } catch (NoClassDefFoundError ignored) {
            return null;
        }
        return null;
    }
}
