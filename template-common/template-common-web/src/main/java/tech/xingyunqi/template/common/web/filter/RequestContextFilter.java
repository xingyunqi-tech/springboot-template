package tech.xingyunqi.template.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.xingyunqi.template.common.web.http.Routes;
import tech.xingyunqi.template.common.web.i18n.I18n;
import tech.xingyunqi.template.common.web.i18n.RequestLanguage;

/** 沿用唯一且合法的入站 X-Request-Id，否则生成 UUID 并回写；按 Accept-Language 设定请求语言。 */
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestContextFilter.class.getName() + ".requestId";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private final I18n i18n;

    public RequestContextFilter(I18n i18n) {
        this.i18n = i18n;
    }

    static String resolveRequestId(List<String> incoming) {
        if (incoming.size() == 1 && REQUEST_ID.matcher(incoming.get(0)).matches()) {
            return incoming.get(0);
        }
        return UUID.randomUUID().toString();
    }

    public static @Nullable String requestId(HttpServletRequest request) {
        return request.getAttribute(REQUEST_ID_ATTRIBUTE) instanceof String id ? id : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(Collections.list(request.getHeaders(REQUEST_ID_HEADER)));
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        String query = request.getQueryString();
        request.setAttribute(
                Routes.ORIGINAL_URL_ATTRIBUTE,
                query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        List<String> languages = Collections.list(request.getHeaders("Accept-Language"));
        RequestLanguage.set(i18n.resolve(languages.isEmpty() ? null : String.join(", ", languages)));
        MDC.put("request_id", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("request_id");
            MDC.remove("user_id");
            RequestLanguage.set(null);
        }
    }
}
