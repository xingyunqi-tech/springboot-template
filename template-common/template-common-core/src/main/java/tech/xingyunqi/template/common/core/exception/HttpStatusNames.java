package tech.xingyunqi.template.common.core.exception;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/** Nest {@code HttpStatus} 枚举名与内置异常的默认描述。 */
public final class HttpStatusNames {

    private static final Map<Integer, String> DESCRIPTIONS = Map.of(
            400, "Bad Request",
            401, "Unauthorized",
            403, "Forbidden",
            404, "Not Found",
            409, "Conflict",
            410, "Gone",
            413, "Payload Too Large",
            422, "Unprocessable Entity",
            429, "Too Many Requests",
            500, "Internal Server Error");

    private static final Set<String> DEFAULT_DESCRIPTION_VALUES = Set.of(
            "Bad Request",
            "Unauthorized",
            "Forbidden",
            "Not Found",
            "Conflict",
            "Gone",
            "Unprocessable Entity",
            "Too Many Requests",
            "Internal Server Error");

    private static final Map<Integer, String> ENUM_NAMES = Map.ofEntries(
            Map.entry(100, "CONTINUE"),
            Map.entry(101, "SWITCHING_PROTOCOLS"),
            Map.entry(102, "PROCESSING"),
            Map.entry(103, "EARLYHINTS"),
            Map.entry(200, "OK"),
            Map.entry(201, "CREATED"),
            Map.entry(202, "ACCEPTED"),
            Map.entry(203, "NON_AUTHORITATIVE_INFORMATION"),
            Map.entry(204, "NO_CONTENT"),
            Map.entry(205, "RESET_CONTENT"),
            Map.entry(206, "PARTIAL_CONTENT"),
            Map.entry(207, "MULTI_STATUS"),
            Map.entry(208, "ALREADY_REPORTED"),
            Map.entry(210, "CONTENT_DIFFERENT"),
            Map.entry(300, "AMBIGUOUS"),
            Map.entry(301, "MOVED_PERMANENTLY"),
            Map.entry(302, "FOUND"),
            Map.entry(303, "SEE_OTHER"),
            Map.entry(304, "NOT_MODIFIED"),
            Map.entry(307, "TEMPORARY_REDIRECT"),
            Map.entry(308, "PERMANENT_REDIRECT"),
            Map.entry(400, "BAD_REQUEST"),
            Map.entry(401, "UNAUTHORIZED"),
            Map.entry(402, "PAYMENT_REQUIRED"),
            Map.entry(403, "FORBIDDEN"),
            Map.entry(404, "NOT_FOUND"),
            Map.entry(405, "METHOD_NOT_ALLOWED"),
            Map.entry(406, "NOT_ACCEPTABLE"),
            Map.entry(407, "PROXY_AUTHENTICATION_REQUIRED"),
            Map.entry(408, "REQUEST_TIMEOUT"),
            Map.entry(409, "CONFLICT"),
            Map.entry(410, "GONE"),
            Map.entry(411, "LENGTH_REQUIRED"),
            Map.entry(412, "PRECONDITION_FAILED"),
            Map.entry(413, "PAYLOAD_TOO_LARGE"),
            Map.entry(414, "URI_TOO_LONG"),
            Map.entry(415, "UNSUPPORTED_MEDIA_TYPE"),
            Map.entry(416, "REQUESTED_RANGE_NOT_SATISFIABLE"),
            Map.entry(417, "EXPECTATION_FAILED"),
            Map.entry(418, "I_AM_A_TEAPOT"),
            Map.entry(421, "MISDIRECTED"),
            Map.entry(422, "UNPROCESSABLE_ENTITY"),
            Map.entry(423, "LOCKED"),
            Map.entry(424, "FAILED_DEPENDENCY"),
            Map.entry(428, "PRECONDITION_REQUIRED"),
            Map.entry(429, "TOO_MANY_REQUESTS"),
            Map.entry(456, "UNRECOVERABLE_ERROR"),
            Map.entry(500, "INTERNAL_SERVER_ERROR"),
            Map.entry(501, "NOT_IMPLEMENTED"),
            Map.entry(502, "BAD_GATEWAY"),
            Map.entry(503, "SERVICE_UNAVAILABLE"),
            Map.entry(504, "GATEWAY_TIMEOUT"),
            Map.entry(505, "HTTP_VERSION_NOT_SUPPORTED"),
            Map.entry(507, "INSUFFICIENT_STORAGE"),
            Map.entry(508, "LOOP_DETECTED"));

    private HttpStatusNames() {}

    /** Nest 内置异常的描述文本；表外状态码取 HTTP 标准短语。 */
    public static String description(int status) {
        String description = DESCRIPTIONS.get(status);
        if (description != null) {
            return description;
        }
        @Nullable HttpStatus known = HttpStatus.resolve(status);
        return known == null ? "Error" : known.getReasonPhrase();
    }

    /** nestjs AllExceptionsFilter 视为「默认描述」的文本，命中时改用状态码默认消息。 */
    public static boolean isDefaultDescription(String text) {
        return DEFAULT_DESCRIPTION_VALUES.contains(text);
    }

    /** Nest {@code HttpStatus[code]}；表外为 {@code Error}。 */
    public static String enumName(int status) {
        return ENUM_NAMES.getOrDefault(status, "Error");
    }
}
