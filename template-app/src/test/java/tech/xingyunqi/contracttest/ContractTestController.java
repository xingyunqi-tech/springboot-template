package tech.xingyunqi.contracttest;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.xingyunqi.template.common.core.exception.BusinessException;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.core.exception.TooManyRequestsException;
import tech.xingyunqi.template.common.web.envelope.ApiResponse;
import tech.xingyunqi.template.common.web.envelope.RawResponse;

@RestController
public class ContractTestController {

    public record AuthEmailLoginDto(String email, String password) {}

    static final Map<String, Supplier<Exception>> ERRORS = Map.ofEntries(
            Map.entry("session", () -> HttpStatusException.notFoundError("sessionNotFound")),
            Map.entry("forbidden", () -> HttpStatusException.forbidden("Missing permissions: user:read, user:list")),
            Map.entry("field", () -> HttpStatusException.validationError("email", "notFound")),
            Map.entry("nested", () -> new HttpStatusException(422, nested())),
            Map.entry(
                    "business",
                    () -> new BusinessException("sessionClosed", ordered("sessionId", "s1", "remaining", 3))),
            Map.entry("throttle", () -> new TooManyRequestsException(Map.of("Retry-After", "60"))),
            Map.entry("plain-string", () -> new HttpStatusException(400, "badRequest")),
            Map.entry("default-not-found", () -> HttpStatusException.of(404, null)),
            Map.entry(
                    "unique-email",
                    () -> new IllegalStateException("wrapped", uniqueViolation("UQ_e12875dfb3b1d92d7d7c5377e22"))),
            Map.entry("unique-favorite", () -> uniqueViolation("UQ_favorite_user_target")),
            Map.entry("unique-order", () -> uniqueViolation("UQ_order_orderNo")),
            Map.entry("unique-unknown", () -> uniqueViolation("some_new_key")),
            Map.entry("unknown", () -> new RuntimeException("boom")));

    private static Map<String, Object> nested() {
        Map<String, Object> errors = ordered("phone", "rateLimited", "retryAfter", 42, "x", Map.of("a", "notFound"));
        return ordered("status", 422, "errors", errors);
    }

    static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static SQLException uniqueViolation(String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C23505\0Mduplicate key\0n" + constraint + "\0"));
    }

    @GetMapping("/api/v1/test/ok")
    Map<String, Object> ok() {
        return ordered("value", 1, "items", List.of(1, 2));
    }

    @PostMapping("/api/v1/test/upload")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> upload() {
        return Map.of("file", Map.of("id", "x"));
    }

    @PostMapping("/api/v1/test/void")
    void voidResult() {}

    @GetMapping("/api/v1/test/null")
    @Nullable
    Object nullData() {
        return null;
    }

    @GetMapping("/api/v1/test/numbers")
    Map<String, Object> numbers() {
        return ordered(
                "integral",
                10.0,
                "negativeZero",
                -0.0,
                "large",
                1.2345678901234568e20,
                "small",
                0.000001,
                "nan",
                Double.NaN,
                "at",
                OffsetDateTime.of(2024, 1, 1, 8, 0, 0, 123_456_000, ZoneOffset.ofHours(8))
                        .toInstant());
    }

    @GetMapping("/api/v1/test/files/{name}")
    Map<String, String> fileName(@PathVariable String name) {
        return Map.of("name", name);
    }

    @GetMapping("/api/v1/test/envelope")
    ApiResponse<List<Integer>> alreadyEnvelope() {
        return new ApiResponse<>(201, "custom", List.of(1));
    }

    @GetMapping("/api/v1/test/raw")
    @RawResponse
    Map<String, Object> raw() {
        return Map.of("plain", true);
    }

    @PostMapping("/api/v1/test/login")
    AuthEmailLoginDto login(@RequestBody AuthEmailLoginDto body) {
        return body;
    }

    @GetMapping("/api/v1/test/items/{id}")
    Map<String, Integer> item(@PathVariable Integer id) {
        return Map.of("id", id);
    }

    @GetMapping("/api/v1/test/text")
    String text() {
        return "plain";
    }

    @GetMapping("/api/v1/test/page")
    Map<String, Integer> page(@org.springframework.web.bind.annotation.RequestParam Integer page) {
        return Map.of("page", page);
    }

    @GetMapping("/api/v1/test/errors/{name}")
    void raise(@PathVariable String name) throws Exception {
        throw ERRORS.get(name).get();
    }
}
