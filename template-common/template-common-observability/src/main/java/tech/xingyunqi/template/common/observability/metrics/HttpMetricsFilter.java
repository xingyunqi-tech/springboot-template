package tech.xingyunqi.template.common.observability.metrics;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.xingyunqi.template.common.web.http.Routes;

/**
 * nestjs {@code MetricsMiddleware}：响应结束时按 method、Express 写法的路由模板（未匹配为 {@code <unmatched>}）与状态码记录请求数与耗时，响应未写完时客户端断开记为
 * {@code 499}。
 */
public class HttpMetricsFilter extends OncePerRequestFilter {

    static final int CLIENT_CLOSED_REQUEST = 499;
    private static final Pattern PARAMETER = Pattern.compile("\\{\\*?([^}:]+)(?::[^}]*)?}");

    private final AppMetrics metrics;

    public HttpMetricsFilter(AppMetrics metrics) {
        this.metrics = metrics;
    }

    /** Spring 路由模板转 Express 写法：{@code /users/{id}} → {@code /users/:id}，{@code /{*path}} → {@code /*path}。 */
    public static String expressPath(String pattern) {
        return PARAMETER
                .matcher(pattern)
                .replaceAll(match -> (match.group().startsWith("{*") ? "*" : ":") + match.group(1));
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        AtomicBoolean recorded = new AtomicBoolean();
        Tracking tracking = new Tracking(response);
        metrics.connectionOpened();
        boolean failed = true;
        try {
            chain.doFilter(request, tracking);
            failed = false;
        } finally {
            if (!failed && request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new Completion(request, tracking, started, recorded));
            } else {
                record(request, tracking, started, recorded, failed);
            }
        }
    }

    private void record(
            HttpServletRequest request, Tracking response, long started, AtomicBoolean recorded, boolean failed) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        metrics.connectionClosed();
        String route = Routes.matchedRoute(request);
        int status;
        if (response.aborted.get()) {
            status = CLIENT_CLOSED_REQUEST;
        } else if (failed && !response.isCommitted()) {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        } else {
            status = response.getStatus();
        }
        metrics.httpRequest(
                request.getMethod(),
                route == null ? AppMetrics.UNMATCHED_ROUTE : expressPath(route),
                Integer.toString(status),
                (System.nanoTime() - started) / 1e9);
    }

    private final class Completion implements AsyncListener {

        private final HttpServletRequest request;
        private final Tracking response;
        private final long started;
        private final AtomicBoolean recorded;

        Completion(HttpServletRequest request, Tracking response, long started, AtomicBoolean recorded) {
            this.request = request;
            this.response = response;
            this.started = started;
            this.recorded = recorded;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            record(request, response, started, recorded, false);
        }

        @Override
        public void onTimeout(AsyncEvent event) {}

        @Override
        public void onError(AsyncEvent event) {
            response.aborted.set(true);
            record(request, response, started, recorded, true);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }

    /** 经输出流写响应体或刷新缓冲时出现 {@link IOException} 视为客户端已断开。 */
    static final class Tracking extends HttpServletResponseWrapper {

        final AtomicBoolean aborted = new AtomicBoolean();
        private @Nullable ServletOutputStream stream;

        Tracking(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                stream = new TrackingStream(super.getOutputStream(), aborted);
            }
            return stream;
        }

        @Override
        public void flushBuffer() throws IOException {
            try {
                super.flushBuffer();
            } catch (IOException e) {
                aborted.set(true);
                throw e;
            }
        }
    }

    private static final class TrackingStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final AtomicBoolean aborted;

        TrackingStream(ServletOutputStream delegate, AtomicBoolean aborted) {
            this.delegate = delegate;
            this.aborted = aborted;
        }

        @Override
        public void write(int b) throws IOException {
            try {
                delegate.write(b);
            } catch (IOException e) {
                aborted.set(true);
                throw e;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                delegate.write(b, off, len);
            } catch (IOException e) {
                aborted.set(true);
                throw e;
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                delegate.flush();
            } catch (IOException e) {
                aborted.set(true);
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } catch (IOException e) {
                aborted.set(true);
                throw e;
            }
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }
}
