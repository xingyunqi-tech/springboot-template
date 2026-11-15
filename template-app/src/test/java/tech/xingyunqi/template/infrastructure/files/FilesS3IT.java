package tech.xingyunqi.template.infrastructure.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tech.xingyunqi.template.auth.AuthApi.ADMIN;
import static tech.xingyunqi.template.auth.AuthApi.JOHN;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.PNG;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.file;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.form;
import static tech.xingyunqi.template.infrastructure.files.FileUploadBodies.upload;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tech.xingyunqi.template.auth.AuthApi;
import tech.xingyunqi.template.common.mybatis.WriteTransactions;
import tech.xingyunqi.template.common.oss.FileDriver;
import tech.xingyunqi.template.common.oss.FileStorage;
import tech.xingyunqi.template.support.TestMinio;
import tech.xingyunqi.template.support.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FilesS3IT {

    static final String BUCKET = "files-s3-it";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        TestMinio.register(registry, "s3", BUCKET);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FileStorage storage;

    @Autowired
    FilesService files;

    @Autowired
    FileMapper mapper;

    @Autowired
    WriteTransactions transactions;

    @Autowired
    FilesAccountCleaner cleaner;

    AuthApi api;
    String john;

    @BeforeEach
    void setUp() throws Exception {
        api = new AuthApi(mvc);
        john = token(JOHN);
    }

    String token(String email) throws Exception {
        return api.loginData(email).path("token").asString();
    }

    JsonNode uploaded(String purpose, String token) throws Exception {
        MockHttpServletResponse response = mvc.perform(upload(purpose, token, form(file("a.png", PNG))))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        return AuthApi.JSON
                .readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .path("data")
                .path("file");
    }

    static String key(JsonNode file) {
        String path = file.path("path").asString();
        String withoutQuery = path.substring(0, path.indexOf('?'));
        return withoutQuery.substring(withoutQuery.lastIndexOf('/') + 1);
    }

    static HttpResponse<byte[]> get(String url) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("上传到桶，path 为路径式 3600 秒预签名 GET URL")
    void uploadsToBucket() throws Exception {
        JsonNode file = uploaded("image", john);
        String key = key(file);

        assertThat(key).matches("[0-9a-f]{21}\\.png");
        assertThat(file.path("path").asString())
                .startsWith(TestMinio.endpoint() + "/" + BUCKET + "/" + key + "?")
                .contains("X-Amz-Expires=3600", "X-Amz-Signature=");
        assertThat(TestMinio.object(BUCKET, key)).isEqualTo(PNG);
        assertThat(get(file.path("path").asString()).body()).isEqualTo(PNG);
        assertThat(TestMinio.client()
                        .headObject(request -> request.bucket(BUCKET).key(key))
                        .contentType())
                .isEqualTo("image/png");
        assertThat(jdbc.queryForObject(
                        "select path from file where id = ?::uuid",
                        String.class,
                        file.path("id").asString()))
                .isEqualTo(key);
    }

    @Test
    @DisplayName("私有文件 302 到预签名 URL；GET files/:path 不注册")
    void privateFileRedirects() throws Exception {
        JsonNode file = uploaded("private-image", john);

        MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.get(
                                "/api/v1/files/private/" + file.path("id").asString())
                        .header("Authorization", "Bearer " + john))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(302);
        String location = response.getHeader("Location");
        assertThat(location).startsWith(TestMinio.endpoint() + "/" + BUCKET + "/");
        assertThat(response.getContentAsString()).isEqualTo("Found. Redirecting to " + location);
        assertThat(get(location).body()).isEqualTo(PNG);
        MockHttpServletResponse html = mvc.perform(MockMvcRequestBuilders.get(
                                "/api/v1/files/private/" + file.path("id").asString())
                        .header("Authorization", "Bearer " + john)
                        .header("Accept", "text/html"))
                .andReturn()
                .getResponse();
        assertThat(html.getContentAsString()).startsWith("<!DOCTYPE html><head><title>Found</title></head>");
        assertThat(api.call("GET", "/api/v1/files/whatever.png", null, null)
                        .body()
                        .path("message")
                        .asString())
                .isEqualTo("Cannot GET /api/v1/files/whatever.png");
    }

    @Test
    @DisplayName("超过用途上限时 413 并删除桶中的对象")
    void oversizedUploadIsDeleted() throws Exception {
        Set<String> before = TestMinio.keys(BUCKET);

        MockHttpServletResponse response = mvc.perform(upload(
                        null, john, form(file("big.png", new byte[5 * 1024 * 1024 + 1]))))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(TestMinio.keys(BUCKET)).isEqualTo(before);
    }

    @Test
    @DisplayName("用户头像 photo.path 同样输出预签名 URL")
    void userPhotoIsPresigned() throws Exception {
        JsonNode file = uploaded("image", john);
        AuthApi.Response updated = api.call(
                "PUT",
                "/api/v1/auth/me",
                Map.of("photo", Map.of("id", file.path("id").asString())),
                john);

        assertThat(updated.data().path("photo").path("path").asString())
                .startsWith(TestMinio.endpoint() + "/" + BUCKET + "/" + key(file) + "?");
    }

    @Test
    @DisplayName("账号清理删除对象；被引用与无归属的文件保留")
    void accountCleanerRemovesObjects() throws Exception {
        String user = registerUser();
        int userId =
                api.call("GET", "/api/v1/auth/me", null, user).data().path("id").asInt();
        JsonNode kept = uploaded("image", user);
        List<JsonNode> removed = List.of(uploaded("image", user), uploaded("private-image", user));
        JsonNode content = uploaded("content-image", token(ADMIN));
        api.call(
                "PUT",
                "/api/v1/auth/me",
                Map.of("photo", Map.of("id", kept.path("id").asString())),
                user);

        cleaner.clean(userId);

        assertThat(TestMinio.object(BUCKET, key(kept))).isEqualTo(PNG);
        assertThat(TestMinio.object(BUCKET, key(content))).isEqualTo(PNG);
        for (JsonNode file : removed) {
            assertThat(TestMinio.object(BUCKET, key(file))).isNull();
            assertThat(jdbc.queryForObject(
                            "select count(*) from file where id = ?::uuid",
                            Integer.class,
                            file.path("id").asString()))
                    .isZero();
        }
    }

    @Test
    @DisplayName("存储删除失败时回滚该记录并抛出，之前的删除保留")
    void storageFailureKeepsEarlierDeletions() throws Exception {
        String user = registerUser();
        int userId =
                api.call("GET", "/api/v1/auth/me", null, user).data().path("id").asInt();
        List<JsonNode> uploaded = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            uploaded.add(uploaded("image", user));
        }
        List<String> ids = uploaded.stream()
                .map(file -> file.path("id").asString())
                .sorted(Comparator.naturalOrder())
                .toList();
        List<String> calls = new ArrayList<>();
        FileStorage flaky = new FailingStorage(storage, calls);
        FilesAccountCleaner failing = new FilesAccountCleaner(files, mapper, flaky, transactions);

        assertThatThrownBy(() -> failing.clean(userId)).isInstanceOf(java.io.UncheckedIOException.class);

        assertThat(calls).hasSize(2);
        assertThat(count(ids.get(0))).isZero();
        assertThat(count(ids.get(1))).isEqualTo(1);
        assertThat(count(ids.get(2))).isEqualTo(1);
        assertThat(TestMinio.object(BUCKET, calls.get(0))).isNull();
        assertThat(TestMinio.object(BUCKET, calls.get(1))).isEqualTo(PNG);
    }

    int count(String id) {
        Integer count = jdbc.queryForObject("select count(*) from file where id = ?::uuid", Integer.class, id);
        return count == null ? 0 : count;
    }

    String registerUser() throws Exception {
        String email = "files-" + java.util.UUID.randomUUID() + "@example.com";
        AuthApi.Response created = api.call(
                "POST",
                "/api/v1/users",
                Map.of(
                        "email",
                        email,
                        "password",
                        AuthApi.PASSWORD,
                        "firstName",
                        "F",
                        "lastName",
                        "L",
                        "role",
                        Map.of("id", 2),
                        "status",
                        Map.of("id", 1)),
                token(ADMIN));
        assertThat(created.status()).isEqualTo(200);
        return token(email);
    }

    /** 第二次删除时失败。 */
    record FailingStorage(FileStorage delegate, List<String> calls) implements FileStorage {

        @Override
        public FileDriver driver() {
            return delegate.driver();
        }

        @Override
        public Path localRoot() {
            return delegate.localRoot();
        }

        @Override
        public Path localFile(String path) {
            return delegate.localFile(path);
        }

        @Override
        public void put(String key, Path source, String contentType) throws IOException {
            delegate.put(key, source, contentType);
        }

        @Override
        public void delete(String path) throws IOException {
            calls.add(path);
            if (calls.size() == 2) {
                throw new IOException("s3 down");
            }
            delegate.delete(path);
        }

        @Override
        public String presignedGetUrl(String key) {
            return delegate.presignedGetUrl(key);
        }

        @Override
        public String presignedPutUrl(String key, long contentLength) {
            return delegate.presignedPutUrl(key, contentLength);
        }
    }
}
