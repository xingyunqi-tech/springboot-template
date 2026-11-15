package tech.xingyunqi.template.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.web.error.ErrorResponseWriter;
import tech.xingyunqi.template.common.web.http.Routes;

/** Express 路由语义：未匹配（含方法不符）返回 404 {@code Cannot <METHOD> <url>}；静态段按原始（未解码）路径不区分大小写匹配。 */
public class ExpressRoutingFilter extends OncePerRequestFilter {

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final ErrorResponseWriter errors;

    public ExpressRoutingFilter(ObjectProvider<RequestMappingHandlerMapping> mappings, ErrorResponseWriter errors) {
        this.mappings = mappings;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpStatusException rejection = check(request);
        if (rejection != null) {
            errors.write(request, response, rejection);
            return;
        }
        chain.doFilter(request, response);
    }

    private @Nullable HttpStatusException check(HttpServletRequest request) {
        HttpServletRequest matchable = request;
        Object previousPath = matchable.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
        ServletRequestPathUtils.parseAndCache(matchable);
        try {
            String pattern = matchedPattern(matchable);
            if (pattern == null) {
                return Routes.cannot(request);
            }
            HttpStatusException rejection = checkRawPath(request.getRequestURI(), pattern, request);
            if (rejection == null) {
                request.setAttribute(Routes.MATCHED_ROUTE_ATTRIBUTE, pattern);
            }
            return rejection;
        } finally {
            matchable.setAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE, previousPath);
            if (previousPath == null) {
                matchable.removeAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
            }
        }
    }

    private @Nullable String matchedPattern(HttpServletRequest request) {
        for (RequestMappingHandlerMapping mapping : mappings) {
            HandlerExecutionChain handler;
            try {
                handler = mapping.getHandler(request);
            } catch (Exception exception) {
                return null;
            }
            if (handler != null) {
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                return pattern == null ? null : pattern.toString();
            }
        }
        return null;
    }

    static @Nullable HttpStatusException checkRawPath(String rawPath, String pattern, HttpServletRequest request) {
        String[] raw = rawPath.split("/", -1);
        String[] template = pattern.split("/", -1);
        for (int i = 0; i < template.length; i++) {
            String segment = template[i];
            if (segment.startsWith("{*") || segment.equals("**")) {
                return null;
            }
            if (i >= raw.length) {
                return Routes.cannot(request);
            }
            if (segment.indexOf('{') < 0 && !segment.toLowerCase(Locale.ROOT).equals(raw[i].toLowerCase(Locale.ROOT))) {
                return Routes.cannot(request);
            }
        }
        return null;
    }
}
