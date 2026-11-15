package tech.xingyunqi.template.common.observability.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tech.xingyunqi.template.common.web.filter.RequestContextFilter;

@ResourceLock("request-logging")
class RequestLoggingFilterTest {

    final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    ILoggingEvent run(MockHttpServletRequest request, int status) throws Exception {
        new RequestLoggingFilter()
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (req, res) -> ((HttpServletResponse) res).setStatus(status));
        return appender.list.getLast();
    }

    static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(kv -> kv.key, kv -> kv.value));
    }

    @ParameterizedTest
    @CsvSource({
        "200,false,INFO",
        "304,false,INFO",
        "404,false,WARN",
        "499,false,WARN",
        "500,false,ERROR",
        "200,true,ERROR"
    })
    @DisplayName("≥500 或异常为 error，≥400 为 warn，其余 info")
    void levelRules(int status, boolean failed, String level) {
        assertThat(RequestLoggingFilter.level(status, failed).name()).isEqualTo(level);
    }

    @Test
    @DisplayName("消息为 <METHOD> <originalUrl> <status>，字段与 pino-http 相同并脱敏")
    void successLine() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.setQueryString("hash=abc&page=1&tag=a&tag=b&token=t");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("Cookie", "sid=1");
        request.addHeader("User-Agent", "probe/1.0");
        request.setAttribute(RequestContextFilter.REQUEST_ID_ATTRIBUTE, "req-1");

        ILoggingEvent event = run(request, 200);

        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .isEqualTo("GET /api/v1/auth/me?hash=abc&page=1&tag=a&tag=b&token=t 200");
        Map<String, Object> fields = fields(event);
        assertThat(fields.get("context")).isEqualTo("HTTP");
        assertThat(fields.get("res")).isEqualTo(Map.of("statusCode", 200));
        assertThat(fields.get("responseTime")).isInstanceOf(Long.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = (Map<String, Object>) fields.get("req");
        assertThat(req)
                .containsKeys("id", "method", "url", "query", "headers", "remoteAddress")
                .containsEntry("id", "req-1")
                .containsEntry("method", "GET")
                .containsEntry("remoteAddress", "203.0.113.7")
                .containsEntry(
                        "query",
                        Map.of("hash", "[REDACTED]", "page", "1", "tag", List.of("a", "b"), "token", "[REDACTED]"));
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) req.get("headers");
        assertThat(headers)
                .containsEntry("authorization", "[REDACTED]")
                .containsEntry("cookie", "[REDACTED]")
                .containsEntry("user-agent", "probe/1.0");
    }

    @Test
    @DisplayName("4xx 为 warn，5xx 为 error 并追加 failed with status code")
    void errorLines() throws Exception {
        assertThat(run(new MockHttpServletRequest("POST", "/x"), 422).getLevel())
                .isEqualTo(Level.WARN);
        ILoggingEvent failed = run(new MockHttpServletRequest("POST", "/x"), 503);
        assertThat(failed.getLevel()).isEqualTo(Level.ERROR);
        assertThat(failed.getFormattedMessage()).isEqualTo("POST /x 503 - failed with status code 503");
    }

    @Test
    @DisplayName("异常穿出时记 error 并追加异常消息")
    void exceptionLine() {
        assertThatThrownBy(() -> new RequestLoggingFilter()
                        .doFilter(
                                new MockHttpServletRequest("GET", "/y"), new MockHttpServletResponse(), (req, res) -> {
                                    throw new IllegalStateException("boom");
                                }))
                .isInstanceOf(IllegalStateException.class);
        ILoggingEvent event = appender.list.getLast();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo("GET /y 500 - boom");
    }

    @Test
    @DisplayName("日志级别低于该条时不输出")
    void respectsLevel() throws Exception {
        logger.setLevel(Level.WARN);
        new RequestLoggingFilter()
                .doFilter(new MockHttpServletRequest("GET", "/"), new MockHttpServletResponse(), (req, res) -> {});
        assertThat(appender.list).isEmpty();
    }
}
