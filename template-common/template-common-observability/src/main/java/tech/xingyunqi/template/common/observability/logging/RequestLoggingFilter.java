package tech.xingyunqi.template.common.observability.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.xingyunqi.template.common.web.filter.RequestContextFilter;
import tech.xingyunqi.template.common.web.http.Routes;

/**
 * pino-http 自动请求日志：每个请求一条 {@code <METHOD> <originalUrl> <status>}（5xx 或异常时追加 {@code - <错误>}）， 字段
 * {@code context}、{@code req}、{@code res}、{@code responseTime}；≥500 或异常为 error、≥400 为 warn、其余 info。
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    static final String REDACTED = "[REDACTED]";
    static final Set<String> REDACT_HEADERS = Set.of("authorization", "cookie");
    static final Set<String> REDACT_QUERY = Set.of("hash", "token");

    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        Throwable error = null;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            error = e;
            throw e;
        } finally {
            int status = error != null && !response.isCommitted()
                    ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    : response.getStatus();
            log(request, status, error, Math.round((System.nanoTime() - started) / 1e6));
        }
    }

    static Level level(int status, boolean failed) {
        if (status >= 500 || failed) {
            return Level.ERROR;
        }
        return status >= 400 ? Level.WARN : Level.INFO;
    }

    static String message(String method, String url, int status, @Nullable Throwable error) {
        String message = method + " " + url + " " + status;
        if (error != null) {
            return message + " - " + error.getMessage();
        }
        return status >= 500 ? message + " - failed with status code " + status : message;
    }

    private static void log(HttpServletRequest request, int status, @Nullable Throwable error, long responseTime) {
        Level level = level(status, error != null);
        if (!LOG.isEnabledForLevel(level)) {
            return;
        }
        String url = Routes.originalUrl(request);
        LOG.atLevel(level)
                .addKeyValue("context", "HTTP")
                .addKeyValue("req", requestFields(request, url))
                .addKeyValue("res", Map.of("statusCode", status))
                .addKeyValue("responseTime", responseTime)
                .log(message(request.getMethod(), url, status, error));
    }

    static Map<String, Object> requestFields(HttpServletRequest request, String url) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", requestId(request));
        fields.put("method", request.getMethod());
        fields.put("url", url);
        fields.put("query", query(request.getQueryString()));
        fields.put("headers", headers(request));
        fields.put("remoteAddress", request.getRemoteAddr());
        return fields;
    }

    private static @Nullable String requestId(HttpServletRequest request) {
        String resolved = RequestContextFilter.requestId(request);
        return resolved != null ? resolved : request.getHeader(RequestContextFilter.REQUEST_ID_HEADER);
    }

    static Map<String, Object> headers(HttpServletRequest request) {
        Map<String, Object> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String key = name.toLowerCase(Locale.ROOT);
            String value = String.join(", ", Collections.list(request.getHeaders(name)));
            headers.put(key, REDACT_HEADERS.contains(key) ? REDACTED : value);
        }
        return headers;
    }

    /** Express {@code req.query}：同名参数多次出现时为数组。 */
    static Map<String, Object> query(@Nullable String queryString) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return query;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            Object previous = query.get(name);
            if (previous == null) {
                query.put(name, value);
            } else if (previous instanceof List<?> list) {
                List<Object> values = new ArrayList<>(list);
                values.add(value);
                query.put(name, values);
            } else {
                query.put(name, List.of(previous, value));
            }
        }
        query.replaceAll((name, value) -> REDACT_QUERY.contains(name) ? REDACTED : value);
        return query;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
