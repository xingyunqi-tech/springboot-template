package tech.xingyunqi.template.common.web.http;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import tools.jackson.databind.json.JsonMapper;

/** 与 Express {@code res.json} 相同的 Content-Type 与 UTF-8 字节输出。 */
public final class JsonResponses {

    public static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private JsonResponses() {}

    public static void write(HttpServletResponse response, int status, Object body, JsonMapper mapper)
            throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        response.setStatus(status);
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(bytes.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
