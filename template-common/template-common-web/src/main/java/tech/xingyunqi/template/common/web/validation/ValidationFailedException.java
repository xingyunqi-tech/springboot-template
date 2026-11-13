package tech.xingyunqi.template.common.web.validation;

import tools.jackson.databind.JsonNode;

/** 校验失败：{@code errors} 为与 class-validator 输出一致的嵌套 map。 */
public final class ValidationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient JsonNode errors;

    public ValidationFailedException(int status, JsonNode errors) {
        super("validation failed: " + errors);
        this.status = status;
        this.errors = errors;
    }

    public int status() {
        return status;
    }

    public JsonNode errors() {
        return errors;
    }
}
