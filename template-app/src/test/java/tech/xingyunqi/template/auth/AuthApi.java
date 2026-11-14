package tech.xingyunqi.template.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MockMvc 上的 HTTP 调用与常用断言数据。 */
public final class AuthApi {

    public static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String ADMIN = "admin@example.com";
    public static final String JOHN = "john.doe@example.com";
    public static final String PASSWORD = md5("Secret00");

    /** 响应：状态码与解析后的 body。 */
    public record Response(int status, JsonNode body, MockHttpServletResponse raw) {
        public JsonNode data() {
            return body.path("data");
        }
    }

    private final MockMvc mvc;

    public AuthApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Response call(String method, String path, @Nullable Object body, @Nullable String token) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), java.net.URI.create(path));
        if (body != null) {
            builder.contentType("application/json")
                    .content(body instanceof String text ? text : JSON.writeValueAsString(body));
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        return new Response(
                response.getStatus(), content.isEmpty() ? JSON.nullNode() : JSON.readTree(content), response);
    }

    public Response login(String email, String password) throws Exception {
        return call(
                "POST",
                "/api/v1/auth/email/login",
                JSON.createObjectNode().put("email", email).put("password", password),
                null);
    }

    public JsonNode loginData(String email) throws Exception {
        return login(email, PASSWORD).data();
    }

    public static JsonNode payload(String token) {
        return JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    public static String md5(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
