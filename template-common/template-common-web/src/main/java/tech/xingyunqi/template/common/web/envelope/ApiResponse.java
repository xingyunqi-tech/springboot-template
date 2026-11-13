package tech.xingyunqi.template.common.web.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.jspecify.annotations.Nullable;

/** 统一信封 {@code {code, message, data}}；返回值已是信封时原样输出。 */
@JsonPropertyOrder({"code", "message", "data"})
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(
        int code, String message, @Nullable T data) {

    public static final String SUCCESS = "success";

    public static <T> ApiResponse<T> success(@Nullable T data) {
        return new ApiResponse<>(200, SUCCESS, data);
    }
}
