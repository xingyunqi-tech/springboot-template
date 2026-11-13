package tech.xingyunqi.template.common.web.http;

import jakarta.servlet.http.HttpServletRequest;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

/** Express 未匹配路由：404 {@code Cannot <METHOD> <originalUrl>}。 */
public final class Routes {

    public static final String ORIGINAL_URL_ATTRIBUTE = Routes.class.getName() + ".originalUrl";

    private Routes() {}

    public static String originalUrl(HttpServletRequest request) {
        Object stored = request.getAttribute(ORIGINAL_URL_ATTRIBUTE);
        if (stored instanceof String url) {
            return url;
        }
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    public static HttpStatusException cannot(HttpServletRequest request) {
        return HttpStatusException.notFound("Cannot " + request.getMethod() + " " + originalUrl(request));
    }
}
