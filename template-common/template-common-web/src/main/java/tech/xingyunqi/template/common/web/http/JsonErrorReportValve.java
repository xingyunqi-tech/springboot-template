package tech.xingyunqi.template.common.web.http;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.function.IntFunction;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.web.filter.CorsFilter;
import tech.xingyunqi.template.common.web.filter.RequestContextFilter;
import tech.xingyunqi.template.common.web.filter.SecurityHeadersFilter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 容器在进入应用前拒绝的请求（如非法百分号转义）输出 JSON 错误信封与全局响应头，替代 Tomcat 的 HTML 错误页。 */
public class JsonErrorReportValve extends ErrorReportValve {

    /** {@code defaultMessage} 为状态码的默认消息（回落语言）。 */
    public record Settings(IntFunction<String> defaultMessage, CorsFilter cors) {}

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static volatile @Nullable Settings settings;

    public static void configure(Settings value) {
        settings = value;
    }

    @Override
    protected void report(Request request, Response response, @Nullable Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        Settings current = settings;
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("code", status);
        envelope.put(
                "message",
                current == null
                        ? tech.xingyunqi.template.common.core.exception.HttpStatusNames.enumName(status)
                        : current.defaultMessage().apply(status));
        envelope.putNull("data");
        byte[] body = MAPPER.writeValueAsBytes(envelope);
        try {
            if (response.getHeader(RequestContextFilter.REQUEST_ID_HEADER) == null) {
                response.setHeader(
                        RequestContextFilter.REQUEST_ID_HEADER,
                        UUID.randomUUID().toString());
            }
            SecurityHeadersFilter.HEADERS.forEach((name, value) -> {
                if (response.getHeader(name) == null) {
                    response.setHeader(name, value);
                }
            });
            if (current != null && response.getHeader("Access-Control-Expose-Headers") == null) {
                current.cors().applySimple(response, request.getHeader("Origin"));
            }
            response.getCoyoteResponse().setContentTypeNoCharset(JsonResponses.CONTENT_TYPE);
            response.setContentLength(body.length);
            try (OutputStream out = response.getOutputStream()) {
                out.write(body);
            }
        } catch (IOException | IllegalStateException ignored) {
            response.setSuspended(true);
        }
    }
}
