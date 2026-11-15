package tech.xingyunqi.template.common.web.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 以 Express 写法输出 {@code application/json; charset=utf-8} 与 Prometheus 文本格式的 {@code Content-Type}（经 Tomcat coyote
 * 响应原样写入）。
 */
public final class ExpressContentType {

    static final String COYOTE_ATTRIBUTE = ExpressContentType.class.getName() + ".coyote";
    private static final Pattern JSON_UTF8 =
            Pattern.compile("application/json\\s*(;\\s*charset\\s*=\\s*\"?utf-8\"?\\s*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMETHEUS_UTF8 = Pattern.compile(
            "text/plain\\s*;\\s*version\\s*=\\s*0\\.0\\.4\\s*;\\s*charset\\s*=\\s*\"?utf-8\"?",
            Pattern.CASE_INSENSITIVE);

    /** Prometheus 文本格式，与 prom-client 输出逐字相同。 */
    public static final String PROMETHEUS = "text/plain; version=0.0.4; charset=utf-8";

    private ExpressContentType() {}

    static @Nullable String verbatim(String contentType) {
        String stripped = contentType.strip();
        if (JSON_UTF8.matcher(stripped).matches()) {
            return JsonResponses.CONTENT_TYPE;
        }
        return PROMETHEUS_UTF8.matcher(stripped).matches() ? PROMETHEUS : null;
    }

    /** 把 coyote 响应放进请求属性，供 {@link Filter} 使用。 */
    public static final class CoyoteValve extends ValveBase {

        public CoyoteValve() {
            super(true);
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            request.setAttribute(COYOTE_ATTRIBUTE, response.getCoyoteResponse());
            getNext().invoke(request, response);
        }
    }

    /** 包装响应：JSON 类型经 coyote 原样写入（无 coyote 时交给容器），其余类型不变。 */
    public static final class Filter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            chain.doFilter(request, new Wrapper(response, coyote(request)));
        }

        private static org.apache.coyote.@Nullable Response coyote(ServletRequest request) {
            return request.getAttribute(COYOTE_ATTRIBUTE) instanceof org.apache.coyote.Response coyote ? coyote : null;
        }
    }

    private static final class Wrapper extends HttpServletResponseWrapper {

        private final org.apache.coyote.@Nullable Response coyote;
        private boolean exact;

        Wrapper(HttpServletResponse response, org.apache.coyote.@Nullable Response coyote) {
            super(response);
            this.coyote = coyote;
        }

        @Override
        public void setContentType(@Nullable String type) {
            String resolved = type == null ? null : verbatim(type);
            if (resolved != null && !isCommitted()) {
                this.exact = true;
                if (coyote != null) {
                    coyote.setContentTypeNoCharset(resolved);
                } else {
                    super.setContentType(resolved);
                }
                return;
            }
            this.exact = false;
            super.setContentType(type);
        }

        @Override
        public void setCharacterEncoding(@Nullable String charset) {
            if (exact && charset != null && charset.toLowerCase(Locale.ROOT).equals("utf-8")) {
                return;
            }
            super.setCharacterEncoding(charset);
        }

        @Override
        public void setHeader(String name, @Nullable String value) {
            if ("content-type".equalsIgnoreCase(name)) {
                setContentType(value);
                return;
            }
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, @Nullable String value) {
            if ("content-type".equalsIgnoreCase(name)) {
                setContentType(value);
                return;
            }
            super.addHeader(name, value);
        }
    }
}
