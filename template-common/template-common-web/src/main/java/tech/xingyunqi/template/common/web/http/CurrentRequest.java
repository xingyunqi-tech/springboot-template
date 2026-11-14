package tech.xingyunqi.template.common.web.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 当前线程绑定的 Servlet 请求。 */
public final class CurrentRequest {

    private CurrentRequest() {}

    public static HttpServletRequest get() {
        if (RequestContextHolder.currentRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        throw new IllegalStateException("no servlet request bound to the current thread");
    }
}
