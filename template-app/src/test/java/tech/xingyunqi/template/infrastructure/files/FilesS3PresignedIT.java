package tech.xingyunqi.template.infrastructure.files;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.xingyunqi.template.auth.AuthApi.JOHN;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.PNG;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.UPLOAD;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.support.TestMinio;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FilesS3PresignedIT {

    static final String BUCKET = "files-s3-presigned-it";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        TestMinio.register(registry, "s3-presigned", BUCKET);
    }

    @Autowired
    MockMvc mvc;

    AuthApi api;
    String john;

    @BeforeEach
    void setUp() throws Exception {
        api = new AuthApi(mvc);
        john = api.loginData(JOHN).path("token").asString();
    }

    @Test
    @DisplayName("返回文件与 PUT 预签名 URL（HTTP 201），按签名 PUT 后可经 path 读取")
    void returnsSignedUploadUrl() throws Exception {
        AuthApi.Response response =
                api.call("POST", UPLOAD, Map.of("fileName", "Photo.JPG", "fileSize", PNG.length), john);

        assertThat(response.status()).isEqualTo(201);
        JsonNode data = response.data();
        assertThat(data.propertyNames()).containsExactly("file", "uploadSignedUrl");
        assertThat(data.path("file").path("mimeType").asString()).isEqualTo("image/jpeg");
        assertThat(data.path("file").path("size").asInt()).isEqualTo(PNG.length);
        String signed = data.path("uploadSignedUrl").asString();
        assertThat(signed).contains("X-Amz-Expires=3600");
        HttpResponse<String> put = HTTP.send(
                HttpRequest.newBuilder(URI.create(signed))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(PNG))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        HttpResponse<byte[]> get = HTTP.send(
                HttpRequest.newBuilder(URI.create(data.path("file").path("path").asString()))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.body()).isEqualTo(PNG);
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(Map.of("fileName", "a.txt", "fileSize", 1), 422, "{\"file\":\"Unsupported file type\"}"),
                Arguments.of(
                        Map.of("fileName", "a.png", "fileSize", 6 * 1024 * 1024),
                        413,
                        "{\"file\":\"File is too large\"}"),
                Arguments.of(
                        Map.of(),
                        422,
                        "{\"fileName\":\"fileName must be a string\",\"fileSize\":"
                                + "\"fileSize must be a number conforming to the specified constraints\"}"));
    }

    @ParameterizedTest
    @MethodSource("errors")
    @DisplayName("文件类型、大小与请求体校验错误")
    void uploadErrors(Map<String, Object> body, int status, String data) throws Exception {
        AuthApi.Response response = api.call("POST", UPLOAD, body, john);

        assertThat(response.status()).isEqualTo(status);
        assertThat(response.data().toString()).isEqualTo(data);
    }

    static Stream<Arguments> fileSizes() {
        return Stream.of(
                Arguments.of("0", 201, "0"),
                Arguments.of("-0", 201, "0"),
                Arguments.of("-1", 201, "-1"),
                Arguments.of("1e3", 201, "1000"),
                Arguments.of("5242880", 201, "5242880"),
                Arguments.of("1.5", 500, "null"),
                Arguments.of("0.1", 500, "null"),
                Arguments.of("5242880.5", 413, "{\"file\":\"File is too large\"}"),
                Arguments.of("2147483648", 413, "{\"file\":\"File is too large\"}"),
                Arguments.of("1e20", 413, "{\"file\":\"File is too large\"}"),
                Arguments.of(
                        "1e400",
                        422,
                        "{\"fileSize\":\"fileSize must be a number conforming to the specified constraints\"}"));
    }

    @ParameterizedTest
    @MethodSource("fileSizes")
    @DisplayName("fileSize：整数原样写入（含 0 与负数），不超过上限的小数 500，超过上限 413，非有限数 422")
    void fileSizeValues(String fileSize, int status, String expected) throws Exception {
        AuthApi.Response response =
                api.call("POST", UPLOAD, "{\"fileName\":\"a.png\",\"fileSize\":" + fileSize + "}", john);

        assertThat(response.status()).isEqualTo(status);
        JsonNode data = response.data();
        String actual = status == 201 ? data.path("file").path("size").toString() : data.toString();
        assertThat(actual).isEqualTo(expected);
        if (status == 500) {
            assertThat(response.body().toString())
                    .isEqualTo("{\"code\":500,\"message\":\"Internal server error\",\"data\":null}");
        }
    }

    @Test
    @DisplayName("用途守卫先于请求体校验")
    void purposeGuardFirst() throws Exception {
        AuthApi.Response response = api.call("POST", UPLOAD + "?purpose=xx", Map.of(), john);

        assertThat(response.data().toString()).isEqualTo("{\"purpose\":\"Unsupported upload purpose\"}");
    }
}
