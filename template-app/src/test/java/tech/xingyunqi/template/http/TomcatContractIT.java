package tech.xingyunqi.template.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TomcatContractIT {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    int port;

    HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://a.example")
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> response) {
        return JsonMapper.builder().build().readTree(response.body());
    }

    @Test
    @DisplayName("Tomcat 下 Content-Type 与 Express 逐字一致")
    void contentTypeMatchesExpress() throws Exception {
        assertThat(send("GET", "/").headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/x/%FF", "/api/v1/x/%C0%80"})
    @DisplayName("容器拒绝的非法转义输出 JSON 信封并带全局响应头")
    void containerRejectionIsJsonEnvelope(String path) throws Exception {
        HttpResponse<String> response = send("GET", path);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
        assertThat(json(response).toString()).isEqualTo("{\"code\":400,\"message\":\"Bad request\",\"data\":null}");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://a.example");
    }

    @Test
    @DisplayName("编码的斜杠交给应用按路由语义处理")
    void encodedSlashReachesApplication() throws Exception {
        assertThat(json(send("GET", "/api/v1/x/1%2F2")).get("message").stringValue())
                .isEqualTo("Cannot GET /api/v1/x/1%2F2");
    }

    @Test
    @DisplayName("TRACE 返回 404 Cannot TRACE")
    void traceIsNotFound() throws Exception {
        HttpResponse<String> response = send("TRACE", "/api/x");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("message").stringValue()).isEqualTo("Cannot TRACE /api/x");
    }
}
