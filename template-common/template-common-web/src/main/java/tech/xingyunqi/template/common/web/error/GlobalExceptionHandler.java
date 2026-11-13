package tech.xingyunqi.template.common.web.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.web.http.Routes;
import tech.xingyunqi.template.common.web.validation.ValidationFailedException;

/** MVC 内异常统一输出 nestjs 信封；未知异常与唯一约束冲突在事务边界之外翻译。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private final ErrorResponseWriter writer;

    public GlobalExceptionHandler(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @ExceptionHandler(ValidationFailedException.class)
    void validationFailed(ValidationFailedException exception, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        HttpStatusException http = new HttpStatusException(
                exception.status(), Map.of("status", exception.status(), "errors", exception.errors()));
        writer.write(request, response, http);
    }

    @ExceptionHandler(MissingPathVariableException.class)
    void missingPathVariable(Exception exception, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        writer.write(request, response, InvalidId.exception());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    void typeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        boolean onPath = exception.getParameter().hasParameterAnnotation(PathVariable.class);
        writer.write(request, response, onPath ? InvalidId.exception() : HttpStatusException.badRequest(null));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    void methodValidation(
            HandlerMethodValidationException exception, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        boolean onPath = exception.getParameterValidationResults().stream()
                .anyMatch(result -> result.getMethodParameter().hasParameterAnnotation(PathVariable.class));
        writer.write(request, response, onPath ? InvalidId.exception() : HttpStatusException.badRequest(null));
    }

    @ExceptionHandler({
        HttpRequestMethodNotSupportedException.class,
        NoHandlerFoundException.class,
        NoResourceFoundException.class
    })
    void notFound(Exception exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        writer.write(request, response, Routes.cannot(request));
    }

    @ExceptionHandler(Exception.class)
    void any(Exception exception, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (exception instanceof ErrorResponse spring && spring.getStatusCode().value() < 500) {
            writer.write(
                    request,
                    response,
                    HttpStatusException.of(spring.getStatusCode().value(), null));
            return;
        }
        writer.write(request, response, exception);
    }
}
