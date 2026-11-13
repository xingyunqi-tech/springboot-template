package tech.xingyunqi.template.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** 与 nestjs helmet 配置相同的安全头集合与取值（HSTS 在所有环境发送）。 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final Map<String, String> HEADERS = headers();

    private static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(
                "Content-Security-Policy",
                "default-src 'self';base-uri 'self';font-src 'self' https: data:;form-action 'self';"
                        + "frame-ancestors 'self';img-src 'self' data: https:;object-src 'none';script-src 'self';"
                        + "script-src-attr 'none';style-src 'self' 'unsafe-inline';upgrade-insecure-requests");
        headers.put("Cross-Origin-Resource-Policy", "cross-origin");
        headers.put("Origin-Agent-Cluster", "?1");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-DNS-Prefetch-Control", "off");
        headers.put("X-Download-Options", "noopen");
        headers.put("X-Frame-Options", "SAMEORIGIN");
        headers.put("X-Permitted-Cross-Domain-Policies", "none");
        headers.put("X-XSS-Protection", "0");
        return Collections.unmodifiableMap(headers);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HEADERS.forEach(response::setHeader);
        chain.doFilter(request, response);
    }
}
