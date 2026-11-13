package tech.xingyunqi.template.common.core.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/** 业务错误：message 为 {@code common.<errorKey>} 译文，data 为 {@code {...details, errorKey}}，不翻译。 */
public class BusinessException extends HttpStatusException {

    public static final String ERROR_KEY_FIELD = "errorKey";

    private final String errorKey;

    public BusinessException(String errorKey) {
        this(errorKey, Map.of());
    }

    public BusinessException(String errorKey, Map<String, ?> details) {
        super(BusinessErrors.status(errorKey), body(errorKey, details));
        this.errorKey = errorKey;
    }

    public String errorKey() {
        return errorKey;
    }

    private static Map<String, Object> body(String errorKey, Map<String, ?> details) {
        Map<String, Object> errors = new LinkedHashMap<>(details);
        errors.put(ERROR_KEY_FIELD, errorKey);
        return HttpStatusException.body("error", errorKey, "errors", errors);
    }
}
