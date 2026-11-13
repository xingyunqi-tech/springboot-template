package tech.xingyunqi.template.common.web.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.xingyunqi.template.common.web.http.JsonResponses;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 过滤器层与控制器层共用的错误输出，保证形状与翻译规则一致。 */
public class ErrorResponseWriter {

    private static final Logger log = LoggerFactory.getLogger("app.http");

    private final ErrorTranslator translator;
    private final JsonMapper mapper;

    public ErrorResponseWriter(ErrorTranslator translator, JsonMapper mapper) {
        this.translator = translator;
        this.mapper = mapper;
    }

    public ErrorBody build(Throwable exception) {
        return translator.build(exception);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, Throwable exception)
            throws IOException {
        ErrorBody body = translator.build(exception);
        if (body.status() >= 500) {
            log.error("{}: {} {}", body.logError(), body.message(), request.getRequestURI(), exception);
        } else {
            log.warn("{}: {} {}", body.logError(), body.message(), request.getRequestURI());
        }
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        for (Map.Entry<String, String> header : body.headers().entrySet()) {
            response.setHeader(header.getKey(), header.getValue());
        }
        JsonResponses.write(response, body.status(), envelope(body), mapper);
    }

    public ObjectNode envelope(ErrorBody body) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("code", body.status());
        node.set("message", body.message());
        node.set("data", body.data() == null ? JsonNodeFactory.instance.nullNode() : body.data());
        return node;
    }
}
