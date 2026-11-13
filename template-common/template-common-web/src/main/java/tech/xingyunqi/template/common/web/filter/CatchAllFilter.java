package tech.xingyunqi.template.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.xingyunqi.template.common.web.error.ErrorResponseWriter;

/** 把控制器异常处理之外抛出的异常转为错误信封。 */
public class CatchAllFilter extends OncePerRequestFilter {

    private final ErrorResponseWriter errors;

    public CatchAllFilter(ErrorResponseWriter errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (Exception exception) {
            Throwable cause = exception instanceof ServletException servlet && servlet.getRootCause() != null
                    ? servlet.getRootCause()
                    : exception;
            if (response.isCommitted()) {
                throw exception;
            }
            errors.write(request, response, cause == null ? exception : cause);
        }
    }
}
