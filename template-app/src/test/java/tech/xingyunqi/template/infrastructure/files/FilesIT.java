package tech.xingyunqi.template.infrastructure.files;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.xingyunqi.template.auth.AuthApi.ADMIN;
import static tech.xingyunqi.template.auth.AuthApi.JOHN;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.PNG;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.UPLOAD;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.field;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.file;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.form;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.stored;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.upload;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = "BACKEND_DOMAIN=http://backend.test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FilesIT {

    static final Path ROOT = FileUploadBodies.tempRoot("files-it");
    static final String INVALID = "Validation failed";
    static final String PURPOSE_ENUM = "purpose must be one of the following values: "
            + "image, private-image, content-image, audio, import, export";

    @DynamicPropertySource
    static void localRoot(DynamicPropertyRegistry registry) {
        registry.add("app.file.local-root", ROOT::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FilesAccountCleaner cleaner;

    AuthApi api;
    String john;

    @BeforeEach
    void setUp() throws Exception {
        FileUploadBodies.clear(ROOT);
        api = new AuthApi(mvc);
        john = token(JOHN);
    }

    String token(String email) throws Exception {
        return api.loginData(email).path("token").asString();
    }

    AuthApi.Response send(MockHttpServletRequestBuilder builder) throws Exception {
        MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        return new AuthApi.Response(
                response.getStatus(),
                content.isEmpty() || !content.startsWith("{")
                        ? AuthApi.JSON.nullNode()
                        : AuthApi.JSON.readTree(content),
                response);
    }

    static String envelope(int code, String message, @Nullable Object data) {
        Map<String, @Nullable Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        return AuthApi.JSON.writeValueAsString(body);
    }

    JsonNode uploaded(String purpose, String token) throws Exception {
        AuthApi.Response response = send(upload(purpose, token, form(file("a.png", PNG))));
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        return response.data().path("file");
    }

    @Test
    @DisplayName("上传返回 HTTP 201、code 200，文件写入存储目录，响应不含 ownerUserId / sha256")
    void uploadStoresFile() throws Exception {
        AuthApi.Response response = send(upload(null, john, form(file("Photo.PNG", PNG))));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().path("code").asInt()).isEqualTo(200);
        JsonNode file = response.data().path("file");
        assertThat(file.propertyNames()).containsExactly("id", "path", "visibility", "mimeType", "size");
        List<String> names = stored(ROOT);
        assertThat(names).hasSize(1);
        String name = names.getFirst();
        assertThat(name).matches("[0-9a-f]{21}\\.png");
        assertThat(file.path("path").asString()).isEqualTo("http://backend.test/api/v1/files/" + name);
        assertThat(file.path("visibility").asString()).isEqualTo("PUBLIC");
        assertThat(file.path("mimeType").asString()).isEqualTo("image/png");
        assertThat(file.path("size").asInt()).isEqualTo(11);
        assertThat(Files.readAllBytes(ROOT.resolve(name))).isEqualTo(PNG);
        int userId =
                api.call("GET", "/api/v1/auth/me", null, john).data().path("id").asInt();
        assertThat(jdbc.queryForMap(
                        "select path, \"ownerUserId\" from file where id = ?::uuid",
                        file.path("id").asString()))
                .containsEntry("path", "/api/v1/files/" + name)
                .containsEntry("ownerUserId", userId);
    }

    @Test
    @DisplayName("私有图片的 path 指向私有文件接口")
    void privateImagePath() throws Exception {
        JsonNode file = uploaded("private-image", john);

        assertThat(file.path("path").asString())
                .isEqualTo("http://backend.test/api/v1/files/private/"
                        + file.path("id").asString());
        assertThat(file.path("visibility").asString()).isEqualTo("PRIVATE");
    }

    static Stream<Arguments> uploadErrors() {
        Map<String, String> badPurpose = Map.of("purpose", "Unsupported upload purpose");
        Map<String, String> badType = Map.of("file", "Unsupported file type");
        return Stream.of(
                Arguments.of("xx", form(file("a.png", PNG)), envelope(422, INVALID, badPurpose)),
                Arguments.of("image&purpose=image", form(file("a.png", PNG)), envelope(422, INVALID, badPurpose)),
                Arguments.of(
                        "audio", form(file("a.png", PNG)), envelope(403, "Missing permissions: content:manage", null)),
                Arguments.of(
                        null,
                        form(file("a.txt", "hi".getBytes(StandardCharsets.UTF_8))),
                        envelope(422, INVALID, badType)),
                Arguments.of(null, form(file("png", PNG)), envelope(422, INVALID, badType)),
                Arguments.of(
                        null,
                        form(new FileUploadBodies.Part("other", "a.png", PNG)),
                        envelope(400, "Unexpected file field - other", null)),
                Arguments.of(
                        null,
                        form(file("a.png", PNG), file("b.png", PNG)),
                        envelope(400, "Unexpected file field - file", null)),
                Arguments.of(null, form(file("", PNG)), envelope(422, INVALID, Map.of("file", "Please select a file"))),
                Arguments.of("", form(file("a.png", PNG)), envelope(422, INVALID, Map.of("purpose", PURPOSE_ENUM))));
    }

    @ParameterizedTest
    @MethodSource("uploadErrors")
    @DisplayName("上传错误与 nestjs 一致，且不留下文件")
    void uploadErrors(@Nullable String purpose, byte[] body, String expected) throws Exception {
        AuthApi.Response response = send(upload(purpose, john, body));

        assertThat(response.body().toString()).isEqualTo(expected);
        assertThat(stored(ROOT)).isEmpty();
    }

    @Test
    @DisplayName("表单中没有文件、JSON 体、空体都返回 selectFile")
    void missingFile() throws Exception {
        String expected = envelope(422, INVALID, Map.of("file", "Please select a file"));
        List<MockHttpServletRequestBuilder> requests = List.of(
                upload(null, john, form(field("x", "1"))),
                upload(null, john, form(field("file", "1"))),
                MockMvcRequestBuilders.post(UPLOAD)
                        .header("Authorization", "Bearer " + john)
                        .contentType("application/json")
                        .content("{}"),
                MockMvcRequestBuilders.post(UPLOAD).header("Authorization", "Bearer " + john));
        for (MockHttpServletRequestBuilder request : requests) {
            assertThat(send(request).body().toString()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("JWT 先于用途检查")
    void requiresJwtFirst() throws Exception {
        AuthApi.Response response = send(upload("xx", "", form(file("a.png", PNG))));

        assertThat(response.body().toString()).isEqualTo(envelope(401, "Unauthorized", null));
    }

    @Test
    @DisplayName("管理员上传内容素材：按扩展名取 mime，文件无归属用户")
    void contentAssetHasNoOwner() throws Exception {
        AuthApi.Response response = send(upload("audio", token(ADMIN), form(file("a.mp3", PNG))));

        JsonNode file = response.data().path("file");
        assertThat(response.status()).isEqualTo(201);
        assertThat(file.path("mimeType").asString()).isEqualTo("audio/mpeg");
        assertThat(jdbc.queryForObject(
                        "select \"ownerUserId\" from file where id = ?::uuid",
                        Integer.class,
                        file.path("id").asString()))
                .isNull();
    }

    @Test
    @DisplayName("畸形 multipart 与缺少 boundary 返回 400")
    void malformedMultipart() throws Exception {
        for (String body : List.of("garbage", "--xyz\r\nBad Header\r\n\r\nx\r\n--xyz--\r\n")) {
            AuthApi.Response response = send(upload(null, john, body.getBytes(StandardCharsets.ISO_8859_1)));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().path("message").asString()).startsWith("Multipart: ");
        }
        AuthApi.Response noBoundary = send(MockMvcRequestBuilders.post(UPLOAD)
                .header("Authorization", "Bearer " + john)
                .contentType("multipart/form-data")
                .content("x"));
        assertThat(noBoundary.body().toString()).isEqualTo(envelope(400, "Multipart: Boundary not found", null));
        AuthApi.Response mixed = send(MockMvcRequestBuilders.post(UPLOAD)
                .header("Authorization", "Bearer " + john)
                .contentType("multipart/mixed; boundary=xyz")
                .content("x"));
        assertThat(mixed.body().toString()).isEqualTo(envelope(415, "Unsupported content type", null));
    }

    @Test
    @DisplayName("文本字段名不合法时 400 并删除已写入的文件")
    void invalidFieldNameRemovesFile() throws Exception {
        AuthApi.Response response = send(upload(null, john, form(file("a.png", PNG), field("a[1001]", "1"))));

        assertThat(response.body().toString())
                .isEqualTo(envelope(400, "Field name array index too large - a[1001]", null));
        assertThat(stored(ROOT)).isEmpty();
    }

    @Test
    @DisplayName("公开文件按 Express sendFile 输出，If-None-Match 命中时 304")
    void publicDownload() throws Exception {
        JsonNode file = uploaded("image", john);
        String url = file.path("path").asString().substring("http://backend.test".length());

        MockHttpServletResponse response =
                mvc.perform(MockMvcRequestBuilders.get(url)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(PNG);
        assertThat(response.getContentType()).isEqualTo("image/png");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=0");
        assertThat(response.getHeader("Accept-Ranges")).isEqualTo("bytes");
        assertThat(response.getHeader("ETag")).startsWith("W/\"b-");
        assertThat(response.getHeader("Last-Modified")).endsWith(" GMT");
        assertThat(response.getContentLength()).isEqualTo(11);

        MockHttpServletResponse cached = mvc.perform(
                        MockMvcRequestBuilders.get(url).header("If-None-Match", response.getHeader("ETag")))
                .andReturn()
                .getResponse();
        assertThat(cached.getStatus()).isEqualTo(304);
        assertThat(cached.getContentAsByteArray()).isEmpty();

        MockHttpServletResponse sinceModified = mvc.perform(MockMvcRequestBuilders.get(url)
                        .header("If-Modified-Since", response.getHeader("Last-Modified")))
                .andReturn()
                .getResponse();
        assertThat(sinceModified.getStatus()).isEqualTo(304);

        MockHttpServletResponse partial = mvc.perform(
                        MockMvcRequestBuilders.get(url).header("Range", "bytes=1-3"))
                .andReturn()
                .getResponse();
        assertThat(partial.getStatus()).isEqualTo(206);
        assertThat(partial.getHeader("Content-Range")).isEqualTo("bytes 1-3/11");
        assertThat(partial.getContentAsByteArray()).isEqualTo(new byte[] {'P', 'N', 'G'});
    }

    @Test
    @DisplayName("不存在、私有文件与 GET files/upload 都是 404 fileNotFound")
    void unknownOrPrivateIsNotFound() throws Exception {
        uploaded("private-image", john);
        String name = stored(ROOT).getFirst();
        String expected = envelope(404, "File not found", null);

        for (String url : List.of("/api/v1/files/nope.png", "/api/v1/files/" + name, UPLOAD)) {
            assertThat(send(MockMvcRequestBuilders.get(url)).body().toString())
                    .as(url)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("私有文件：本人可读，他人 403，未登录 401；公开文件任何登录用户可读")
    void privateFileAccess() throws Exception {
        String admin = token(ADMIN);
        JsonNode privateFile = uploaded("private-image", john);
        String url = "/api/v1/files/private/" + privateFile.path("id").asString();

        MockHttpServletResponse allowed = mvc.perform(
                        MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + john))
                .andReturn()
                .getResponse();
        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(allowed.getContentAsByteArray()).isEqualTo(PNG);
        assertThat(send(MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + admin))
                        .body()
                        .toString())
                .isEqualTo(envelope(403, "You cannot access this file", null));
        assertThat(send(MockMvcRequestBuilders.get(url)).body().toString())
                .isEqualTo(envelope(401, "Unauthorized", null));

        JsonNode publicFile = uploaded("image", john);
        MockHttpServletResponse viaPrivate = mvc.perform(MockMvcRequestBuilders.get(
                                "/api/v1/files/private/" + publicFile.path("id").asString())
                        .header("Authorization", "Bearer " + admin))
                .andReturn()
                .getResponse();
        assertThat(viaPrivate.getStatus()).isEqualTo(200);
        assertThat(viaPrivate.getContentAsByteArray()).isEqualTo(PNG);
    }

    static Stream<Arguments> privateIds() {
        String invalid = "Validation failed (uuid is expected)";
        return Stream.of(
                Arguments.of("abc", envelope(400, invalid, null)),
                Arguments.of("00000000-0000-9000-0000-000000000000", envelope(400, invalid, null)),
                Arguments.of("00000000-0000-0000-0000-000000000000", envelope(404, "File not found", null)),
                Arguments.of("AAAAAAAA-AAAA-1AAA-8AAA-AAAAAAAAAAAA", envelope(404, "File not found", null)));
    }

    @ParameterizedTest
    @MethodSource("privateIds")
    @DisplayName("私有文件 id 按 ParseUUIDPipe 校验")
    void privateIdValidation(String id, String expected) throws Exception {
        AuthApi.Response response = send(
                MockMvcRequestBuilders.get("/api/v1/files/private/" + id).header("Authorization", "Bearer " + john));

        assertThat(response.body().toString()).isEqualTo(expected);
    }

    @Test
    @DisplayName("账号清理删除该用户的文件与磁盘文件，被引用与无归属的内容素材保留")
    void accountCleanerRemovesOwnedFiles() throws Exception {
        int userId =
                api.call("GET", "/api/v1/auth/me", null, john).data().path("id").asInt();
        JsonNode kept = uploaded("image", john);
        List<JsonNode> removed = List.of(uploaded("image", john), uploaded("private-image", john));
        JsonNode content = uploaded("content-image", token(ADMIN));
        assertThat(api.call(
                                "PUT",
                                "/api/v1/auth/me",
                                Map.of("photo", Map.of("id", kept.path("id").asString())),
                                john)
                        .status())
                .isEqualTo(200);
        Map<String, String> paths = paths();

        cleaner.clean(userId);

        Map<String, String> remaining = paths();
        assertThat(remaining)
                .containsKeys(kept.path("id").asString(), content.path("id").asString());
        for (JsonNode file : removed) {
            String id = file.path("id").asString();
            assertThat(remaining).doesNotContainKey(id);
            assertThat(ROOT.resolve(fileName(paths.get(id)))).doesNotExist();
        }
        assertThat(ROOT.resolve(fileName(paths.get(kept.path("id").asString()))))
                .exists();
    }

    Map<String, String> paths() {
        Map<String, String> paths = new java.util.HashMap<>();
        jdbc.query("select id::text as id, path from file", row -> {
            paths.put(row.getString("id"), row.getString("path"));
        });
        return paths;
    }

    static String fileName(@Nullable String path) {
        assertThat(path).isNotNull();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
