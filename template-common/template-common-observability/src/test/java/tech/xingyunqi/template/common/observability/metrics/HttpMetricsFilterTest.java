package tech.xingyunqi.template.common.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tech.xingyunqi.template.common.web.http.Routes;

class HttpMetricsFilterTest {

    final AppMetrics metrics = new AppMetrics("app");
    final HttpMetricsFilter filter = new HttpMetricsFilter(metrics);

    @ParameterizedTest
    @CsvSource({
        "/api/v1/users/{id},/api/v1/users/:id",
        "/api/v1/files/{path},/api/v1/files/:path",
        "/api/v1/a/{id}/b/{name},/api/v1/a/:id/b/:name",
        "/files/{*path},/files/*path",
        "/,/"
    })
    @DisplayName("Spring 路由模板转 Express 写法")
    void expressPath(String pattern, String expected) {
        assertThat(HttpMetricsFilter.expressPath(pattern)).isEqualTo(expected);
    }

    @Test
    @DisplayName("命中的路由记录模板，未命中记录 <unmatched>，结束后活动连接归零")
    void routeLabels() throws Exception {
        MockHttpServletRequest matched = new MockHttpServletRequest("GET", "/api/v1/users/12");
        filter.doFilter(matched, new MockHttpServletResponse(), (request, response) -> {
            request.setAttribute(Routes.MATCHED_ROUTE_ATTRIBUTE, "/api/v1/users/{id}");
            assertThat(metrics.value("http_active_connections")).isEqualTo(1);
            ((HttpServletResponse) response).setStatus(401);
        });
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/nope"),
                new MockHttpServletResponse(),
                (request, response) -> ((HttpServletResponse) response).setStatus(404));

        assertThat(metrics.value("http_requests_total", "GET", "/api/v1/users/:id", "401"))
                .isEqualTo(1);
        assertThat(metrics.value("http_requests_total", "GET", "<unmatched>", "404"))
                .isEqualTo(1);
        assertThat(metrics.value("http_request_duration_seconds", "GET", "<unmatched>", "404"))
                .isEqualTo(1);
        assertThat(metrics.value("http_active_connections")).isZero();
    }

    @Test
    @DisplayName("写响应体时客户端断开记为 499")
    void clientDisconnectIs499() {
        MockHttpServletResponse disconnected = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        throw new IOException("Broken pipe");
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {}
                };
            }
        };
        assertThatThrownBy(() ->
                        filter.doFilter(new MockHttpServletRequest("GET", "/"), disconnected, (request, response) -> {
                            request.setAttribute(Routes.MATCHED_ROUTE_ATTRIBUTE, "/");
                            response.getOutputStream().write(new byte[] {1, 2}, 0, 2);
                        }))
                .isInstanceOf(IOException.class);

        assertThat(metrics.value("http_requests_total", "GET", "/", "499")).isEqualTo(1);
    }

    @Test
    @DisplayName("异常穿出且响应未提交时记为 500")
    void uncaughtExceptionIs500() {
        assertThatThrownBy(() -> filter.doFilter(
                        new MockHttpServletRequest("POST", "/x"),
                        new MockHttpServletResponse(),
                        (request, response) -> {
                            throw new IllegalStateException("boom");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(metrics.value("http_requests_total", "POST", "<unmatched>", "500"))
                .isEqualTo(1);
    }
}
