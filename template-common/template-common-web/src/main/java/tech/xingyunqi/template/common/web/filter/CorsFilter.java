package tech.xingyunqi.template.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/** cors@2.8：{@code origins} 为 null 时回显来源与请求头，否则按白名单；OPTIONS 一律 204。 */
public class CorsFilter extends OncePerRequestFilter {

    public static final String DEFAULT_METHODS = "GET,HEAD,PUT,PATCH,POST,DELETE";
    public static final String PRODUCTION_METHODS = "GET,POST,PUT,PATCH,DELETE,OPTIONS";
    public static final String PRODUCTION_HEADERS = "Content-Type,Authorization,Accept-Language";
    public static final String EXPOSED_HEADERS =
            "X-Request-Id,Retry-After,X-RateLimit-Limit,X-RateLimit-Remaining,X-RateLimit-Reset";

    private final @Nullable List<String> origins;
    private final String methods;
    private final @Nullable String allowedHeaders;

    public CorsFilter(@Nullable List<String> origins, String methods, @Nullable String allowedHeaders) {
        this.origins = origins == null ? null : List.copyOf(origins);
        this.methods = methods;
        this.allowedHeaders = allowedHeaders;
    }

    public static CorsFilter reflecting() {
        return new CorsFilter(null, DEFAULT_METHODS, null);
    }

    public static CorsFilter production(String frontendDomain) {
        List<String> origins =
                Arrays.stream(frontendDomain.split(",")).map(String::strip).toList();
        return new CorsFilter(origins, PRODUCTION_METHODS, PRODUCTION_HEADERS);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        applyOrigin(response, origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setHeader("Access-Control-Allow-Methods", methods);
            String allowed = allowedHeaders;
            if (allowed == null) {
                allowed = request.getHeader("Access-Control-Request-Headers");
                vary(response, "Access-Control-Request-Headers");
            }
            if (allowed != null && !allowed.isEmpty()) {
                response.setHeader("Access-Control-Allow-Headers", allowed);
            }
            response.setHeader("Access-Control-Expose-Headers", EXPOSED_HEADERS);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            response.setContentLength(0);
            return;
        }
        response.setHeader("Access-Control-Expose-Headers", EXPOSED_HEADERS);
        chain.doFilter(request, response);
    }

    /** 非预检响应的 CORS 头。 */
    public void applySimple(HttpServletResponse response, @Nullable String origin) {
        applyOrigin(response, origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader("Access-Control-Expose-Headers", EXPOSED_HEADERS);
    }

    private void applyOrigin(HttpServletResponse response, @Nullable String origin) {
        boolean allowed = origins == null || (origin != null && origins.contains(origin));
        if (allowed && origin != null && !origin.isEmpty()) {
            response.setHeader("Access-Control-Allow-Origin", origin);
        }
        vary(response, "Origin");
    }

    static void vary(HttpServletResponse response, String field) {
        String current = response.getHeader("Vary");
        if (current == null) {
            response.setHeader("Vary", field);
            return;
        }
        if (current.strip().equals("*")) {
            return;
        }
        List<String> existing = Arrays.stream(current.split(","))
                .map(v -> v.strip().toLowerCase(Locale.ROOT))
                .toList();
        List<String> additions = new ArrayList<>();
        for (String value : field.split(",")) {
            String trimmed = value.strip();
            if (!existing.contains(trimmed.toLowerCase(Locale.ROOT))) {
                additions.add(trimmed);
            }
        }
        if (additions.contains("*")) {
            response.setHeader("Vary", "*");
        } else if (!additions.isEmpty()) {
            response.setHeader("Vary", current + ", " + String.join(", ", additions));
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }
}
