package tech.xingyunqi.template.common.web.http;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

/** Express 未匹配路由：404 {@code Cannot <METHOD> <originalUrl>}。 */
public final class Routes {

    public static final String ORIGINAL_URL_ATTRIBUTE = Routes.class.getName() + ".originalUrl";
    public static final String MATCHED_ROUTE_ATTRIBUTE = Routes.class.getName() + ".matchedRoute";

    private Routes() {}

    public static String originalUrl(HttpServletRequest request) {
        Object stored = request.getAttribute(ORIGINAL_URL_ATTRIBUTE);
        if (stored instanceof String url) {
            return url;
        }
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    /** 已命中的 handler 路由模板（含 API 前缀，Spring 写法）；未匹配的请求为 null。 */
    public static @Nullable String matchedRoute(HttpServletRequest request) {
        return request.getAttribute(MATCHED_ROUTE_ATTRIBUTE) instanceof String route ? route : null;
    }

    public static HttpStatusException cannot(HttpServletRequest request) {
        return HttpStatusException.notFound("Cannot " + request.getMethod() + " " + originalUrl(request));
    }
}
