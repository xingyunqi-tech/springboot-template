package tech.xingyunqi.template.common.core.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** 与 NestJS {@code HttpException} 等价：{@code response} 为字符串、对象（message / error / errors）或空。 */
public class HttpStatusException extends RuntimeException {

    private final int status;
    private final @Nullable Object response;
    private final Map<String, String> headers;

    public HttpStatusException(int status, @Nullable Object response) {
        this(status, response, Map.of());
    }

    public HttpStatusException(int status, @Nullable Object response, Map<String, String> headers) {
        super(response == null ? null : String.valueOf(response), null, false, false);
        this.status = status;
        this.response = response;
        this.headers = Map.copyOf(headers);
    }

    /** Nest 内置异常（{@code NotFoundException} 等）的 createBody 规则。 */
    public static HttpStatusException of(int status, @Nullable Object objectOrMessage) {
        String description = HttpStatusNames.description(status);
        if (objectOrMessage == null) {
            return new HttpStatusException(status, body("statusCode", status, "message", description));
        }
        if (objectOrMessage instanceof String message) {
            return new HttpStatusException(
                    status, body("statusCode", status, "message", message, "error", description));
        }
        return new HttpStatusException(status, objectOrMessage);
    }

    public static HttpStatusException badRequest(@Nullable Object body) {
        return of(400, body);
    }

    public static HttpStatusException unauthorized(@Nullable Object body) {
        return of(401, body);
    }

    public static HttpStatusException forbidden(@Nullable Object body) {
        return of(403, body);
    }

    public static HttpStatusException notFound(@Nullable Object body) {
        return of(404, body);
    }

    public static HttpStatusException conflict(@Nullable Object body) {
        return of(409, body);
    }

    public static HttpStatusException unprocessable(@Nullable Object body) {
        return of(422, body);
    }

    /** nestjs {@code throwValidationError(field, error)}。 */
    public static HttpStatusException validationError(String field, Object error) {
        return validationErrors(Map.of(field, error));
    }

    public static HttpStatusException validationErrors(Map<String, ?> errors) {
        return unprocessable(body("status", 422, "errors", new LinkedHashMap<>(errors)));
    }

    /** nestjs {@code throwNotFoundError(error)}：message 为 {@code common.<error>} 译文。 */
    public static HttpStatusException notFoundError(String error) {
        return notFound(body("status", 404, "error", error));
    }

    public int status() {
        return status;
    }

    public @Nullable Object response() {
        return response;
    }

    public Map<String, String> headers() {
        return headers;
    }

    static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
