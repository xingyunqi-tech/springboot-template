package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/** 与 fastapi-temp {@code tests/parity/test_files_parity.py} 一一对应（nestjs 参照服务 {@code FILE_DRIVER=local}）。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilesParityIT {

    static final String UPLOAD = "/api/v1/files/upload";
    static final String ADMIN = "admin@example.com";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 'a', 'b', 'c'};
    static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final Pattern STORED_NAME = Pattern.compile("/files/[^/]+\\.(\\w+)$");
    static final String RANDOM_BOUNDARY = "parityboundary7f3a9c";

    private final Parity parity = Parity.fromSystemProperties();
    private final AuthParityIT auth = new AuthParityIT();
    private AuthParityIT.TempUser user;
    private List<String> admin;
    private @Nullable List<JsonNode> sendFiles;

    /** multipart 分段：{@code fileName} 为 null 时是文本字段。 */
    record Part(
            String name,
            @Nullable String fileName,
            byte[] content,
            @Nullable String contentType) {}

    /** 一个上传用例：查询参数、请求头、请求体。 */
    record Upload(String label, String query, Map<String, String> headers, byte[] body) {
        @Override
        public String toString() {
            return label;
        }
    }

    @BeforeAll
    void createUser() {
        user = auth.tempUser("files");
        admin = auth.tokens(ADMIN);
    }

    @AfterAll
    void dropUser() {
        if (user != null) {
            ParityDb.dropUser(user.email());
        }
    }

    static Part filePart(String fileName, byte[] content, @Nullable String contentType) {
        return new Part("file", fileName, content, contentType);
    }

    static byte[] multipartBody(List<Part> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Part part : parts) {
            StringBuilder head = new StringBuilder("--" + RANDOM_BOUNDARY + "\r\n");
            head.append("Content-Disposition: form-data; name=\"")
                    .append(part.name())
                    .append('"');
            if (part.fileName() != null && !part.fileName().isEmpty()) {
                head.append("; filename=\"").append(part.fileName()).append('"');
            }
            head.append("\r\n");
            if (part.fileName() != null) {
                head.append("Content-Type: ")
                        .append(part.contentType() == null ? "application/octet-stream" : part.contentType())
                        .append("\r\n");
            }
            head.append("\r\n");
            out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes(part.content());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + RANDOM_BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    static Upload files(String label, String query, Part... parts) {
        return new Upload(
                label,
                query,
                Map.of("Content-Type", "multipart/form-data; boundary=" + RANDOM_BOUNDARY),
                multipartBody(List.of(parts)));
    }

    static Upload raw(String label, String contentType, byte[] body) {
        return new Upload(label, "", Map.of("Content-Type", contentType), body);
    }

    static byte[] latin1(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] disposition(String params) {
        return latin1("--xyz\r\nContent-Disposition: form-data; " + params + "\r\n\r\nabc\r\n--xyz--\r\n");
    }

    static byte[] zeros(int length) {
        return new byte[length];
    }

    static List<Upload> uploads() {
        Part png = filePart("a.png", PNG, "image/png");
        String multipart = "multipart/form-data; boundary=xyz";
        byte[] truncated = disposition("name=\"file\"; filename=\"a.png\"");
        return List.of(
                files("png", "", png),
                files("private-image", "purpose=private-image", png),
                files("tar.JPEG", "", filePart("Photo.tar.JPEG", PNG, "image/jpeg")),
                files("dir name", "", filePart("../../x.png", PNG, "image/png")),
                files("empty file", "", filePart("empty.png", new byte[0], "image/png")),
                files("bad purpose", "purpose=xx", png),
                files("repeated purpose", "purpose=image&purpose=image", png),
                files("empty purpose", "purpose=", new Part("x", null, latin1("1"), null)),
                files("audio forbidden", "purpose=audio", filePart("a.mp3", PNG, "audio/mpeg")),
                files("txt", "", filePart("a.txt", latin1("hi"), "text/plain")),
                files("no extension", "", filePart("png", PNG, "image/png")),
                files("other field", "", new Part("other", "a.png", PNG, "image/png")),
                files("two files", "", filePart("a.png", PNG, null), filePart("b.png", PNG, null)),
                files("empty filename", "", filePart("", PNG, "image/png")),
                files("text file field", "", new Part("file", null, latin1("text"), null)),
                raw("json", "application/json", latin1("{\"fileName\":\"a.png\",\"fileSize\":10}")),
                new Upload("no body", "", Map.of(), new byte[0]),
                raw("urlencoded", "application/x-www-form-urlencoded", latin1("file=abc")),
                files("over purpose limit", "", filePart("big.png", zeros(5 * 1024 * 1024 + 1), "image/png")),
                files("over content length", "", filePart("big.png", zeros(5 * 1024 * 1024 + 20000), "image/png")),
                raw("garbage", multipart, latin1("garbage")),
                raw("bad header", multipart, latin1("--xyz\r\nBad Header\r\n\r\nabc\r\n--xyz--\r\n")),
                raw(
                        "preamble",
                        multipart,
                        concat(latin1("preamble\r\n"), disposition("name=\"file\"; filename=\"a.png\""))),
                raw("missing name", multipart, disposition("filename=\"a.png\"")),
                raw("truncated", multipart, Arrays.copyOf(truncated, truncated.length - 11)),
                raw("no boundary", "multipart/form-data", latin1("x")),
                raw("multipart/mixed", "multipart/mixed; boundary=xyz", latin1("x")));
    }

    static Stream<Arguments> uploadCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String lang : List.of("en", "zh-CN")) {
            for (Upload upload : uploads()) {
                cases.add(Arguments.of(upload, lang));
            }
        }
        return cases.stream();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Arrays.stream(parts).forEach(out::writeBytes);
        return out.toByteArray();
    }

    static JsonNode normalizeFile(JsonNode value, @Nullable String key) {
        if (value instanceof ObjectNode object) {
            ObjectNode copy = Parity.JSON.createObjectNode();
            object.properties().forEach(e -> copy.set(e.getKey(), normalizeFile(e.getValue(), e.getKey())));
            return copy;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode copy = Parity.JSON.createArrayNode();
            array.forEach(item -> copy.add(normalizeFile(item, null)));
            return copy;
        }
        JsonNode result = value;
        if (value.isString() && ("id".equals(key) || "path".equals(key))) {
            String text = UUID.matcher(value.stringValue()).replaceAll("<uuid>");
            if ("path".equals(key) && !text.contains("/private/")) {
                text = STORED_NAME.matcher(text).replaceAll("/files/<name>.$1");
            }
            result = StringNode.valueOf(text);
        }
        return AuthParityIT.normalize(result, key);
    }

    static JsonNode assertSame(Parity.Pair pair) {
        Parity.Result java = pair.java();
        Parity.Result nestjs = pair.nestjs();
        String detail = new String(java.body(), StandardCharsets.UTF_8) + "\n"
                + new String(nestjs.body(), StandardCharsets.UTF_8);
        assertThat(java.status()).as(detail).isEqualTo(nestjs.status());
        assertThat(java.header("content-type")).as(detail).isEqualTo(nestjs.header("content-type"));
        Parity.assertSameJson(normalizeFile(java.json(), null), normalizeFile(nestjs.json(), null), detail);
        return java.json();
    }

    Parity.Pair authed(String method, String path, List<String> tokens, Map<String, String> headers, byte[] body) {
        Map<String, String> javaHeaders = new LinkedHashMap<>(headers);
        Map<String, String> nestjsHeaders = new LinkedHashMap<>(headers);
        javaHeaders.put("Authorization", "Bearer " + tokens.get(0));
        nestjsHeaders.put("Authorization", "Bearer " + tokens.get(1));
        return parity.requestEach(method, path, javaHeaders, path, nestjsHeaders, body);
    }

    static String withQuery(String path, String query) {
        return query.isEmpty() ? path : path + "?" + query;
    }

    @ParameterizedTest
    @MethodSource("uploadCases")
    void upload(Upload upload, String lang) {
        Map<String, String> headers = new LinkedHashMap<>(upload.headers());
        headers.put("Accept-Language", lang);

        assertSame(authed("POST", withQuery(UPLOAD, upload.query()), user.tokens(), headers, upload.body()));
    }

    @Test
    void uploadRequiresJwt() {
        Upload upload = files("png", "purpose=xx", filePart("a.png", PNG, "image/png"));

        assertSame(parity.request("POST", withQuery(UPLOAD, upload.query()), upload.headers(), upload.body()));
    }

    @Test
    void adminUploadsContentAsset() {
        Upload upload = files("m4a", "purpose=audio", filePart("a.m4a", PNG, "audio/mp4"));

        assertSame(authed("POST", withQuery(UPLOAD, upload.query()), admin, upload.headers(), upload.body()));
    }

    List<JsonNode> uploadPair(String purpose) {
        Upload upload = files(
                "png",
                "purpose=" + URLEncoder.encode(purpose, StandardCharsets.UTF_8),
                filePart("a.png", PNG, "image/png"));
        Parity.Pair pair =
                authed("POST", withQuery(UPLOAD, upload.query()), user.tokens(), upload.headers(), upload.body());
        return List.of(
                pair.java().json().path("data").path("file"),
                pair.nestjs().json().path("data").path("file"));
    }

    static String storedName(JsonNode file) {
        String path = file.path("path").asString();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Test
    void publicFileDownload() {
        List<JsonNode> files = uploadPair("image");
        Parity.Pair pair = parity.requestEach(
                "GET",
                "/api/v1/files/" + storedName(files.get(0)),
                Map.of(),
                "/api/v1/files/" + storedName(files.get(1)),
                Map.of(),
                new byte[0]);

        assertThat(pair.java().status()).isEqualTo(pair.nestjs().status());
        assertThat(pair.java().body()).isEqualTo(pair.nestjs().body());
        for (String header : List.of("content-type", "cache-control", "accept-ranges", "content-length")) {
            assertThat(pair.java().header(header))
                    .as(header)
                    .isEqualTo(pair.nestjs().header(header));
        }
        assertThat(pair.java().header("etag")).startsWith("W/\"b-");
        assertThat(pair.nestjs().header("etag")).startsWith("W/\"b-");
    }

    static final List<String> SEND_HEADERS =
            List.of("content-type", "content-length", "content-range", "accept-ranges", "cache-control");

    /** 公开文件下载的条件请求与范围请求；{@code {etag}}、{@code {lm}} 替换为各自上一次响应的 ETag 与 Last-Modified。 */
    static Stream<Arguments> sendCases() {
        return Stream.of(
                Arguments.of("GET", Map.of("Range", "bytes=999999-")),
                Arguments.of("HEAD", Map.of("Range", "bytes=999999-")),
                Arguments.of("GET", Map.of("Range", "bytes=999999-", "Accept-Language", "zh")),
                Arguments.of("GET", Map.of("If-Match", "\"x\"")),
                Arguments.of("HEAD", Map.of("If-Match", "\"x\"")),
                Arguments.of("GET", Map.of("If-Match", "{etag}")),
                Arguments.of("GET", Map.of("If-Unmodified-Since", "Mon, 01 Jan 2001 00:00:00 GMT")),
                Arguments.of("GET", Map.of("If-None-Match", "{etag}")),
                Arguments.of("HEAD", Map.of("If-None-Match", "{etag}")),
                Arguments.of("GET", Map.of("If-None-Match", "{etag}", "Cache-Control", "no-cache")),
                Arguments.of("GET", Map.of("If-Modified-Since", "{lm}")),
                Arguments.of("GET", Map.of("If-None-Match", "{etag}", "Range", "bytes=999999-")),
                Arguments.of("GET", Map.of("If-Match", "\"x\"", "Range", "bytes=999999-")),
                Arguments.of("GET", Map.of("Range", "bytes=0-4")),
                Arguments.of("HEAD", Map.of("Range", "bytes=0-4")),
                Arguments.of("GET", Map.of("Range", "bytes=-3")),
                Arguments.of("GET", Map.of("Range", "bytes=0-1,2-3")),
                Arguments.of("GET", Map.of("Range", "bytes=0-1,4-5")),
                Arguments.of("GET", Map.of("Range", "bytes= 1 - 2 ")),
                Arguments.of("GET", Map.of("Range", "items=0-1")),
                Arguments.of("GET", Map.of("Range", "bytes=abc")),
                Arguments.of("GET", Map.of("Range", "bytes=0-4", "If-Range", "{etag}")),
                Arguments.of("GET", Map.of("Range", "bytes=0-4", "If-Range", "\"x\"")),
                Arguments.of("GET", Map.of("Range", "bytes=0-4", "If-Range", "{lm}")),
                Arguments.of("GET", Map.of("Range", "bytes=0-4", "If-Range", "Mon, 01 Jan 2001 00:00:00 GMT")),
                Arguments.of("GET", Map.of("Range", "bytes=999999-", "If-Range", "\"x\"")),
                Arguments.of("GET", Map.of("Range", "bytes=")),
                Arguments.of("GET", Map.of("Range", "bytes=5-2")),
                Arguments.of("GET", Map.of("Range", "bytes=-0")),
                Arguments.of("GET", Map.of("Range", "bytes=3-")),
                Arguments.of("GET", Map.of("Range", "bytes=5-100")),
                Arguments.of("GET", Map.of("Range", "bytes=0-1,1-2")),
                Arguments.of("GET", Map.of("If-None-Match", "*")));
    }

    /** 重复的请求头：Range / If-None-Match 按 Node 规则以逗号合并，If-Modified-Since 只取第一个。 */
    static Stream<Arguments> repeatedHeaderCases() {
        return Stream.of(
                Arguments.of(List.of(Map.entry("Range", "bytes=0-1"), Map.entry("Range", "bytes=0-1"))),
                Arguments.of(List.of(Map.entry("If-None-Match", "W/\"x\""), Map.entry("If-None-Match", "{etag}"))),
                Arguments.of(List.of(
                        Map.entry("If-Modified-Since", "Thu, 01 Jan 1970 00:00:00 GMT"),
                        Map.entry("If-Modified-Since", "{lm}"))));
    }

    static Map<String, String> resolve(Map<String, String> headers, Parity.Result previous) {
        Map<String, String> resolved = new LinkedHashMap<>();
        headers.forEach((name, value) -> resolved.put(
                name,
                value.replace("{etag}", previous.header("etag")).replace("{lm}", previous.header("last-modified"))));
        return resolved;
    }

    @ParameterizedTest
    @MethodSource("sendCases")
    void publicFileConditionalAndRange(String method, Map<String, String> headers) {
        if (sendFiles == null) {
            sendFiles = uploadPair("image");
        }
        List<JsonNode> files = sendFiles;
        String javaPath = "/api/v1/files/" + storedName(files.get(0));
        String nestjsPath = "/api/v1/files/" + storedName(files.get(1));
        Parity.Pair first = parity.requestEach("GET", javaPath, Map.of(), nestjsPath, Map.of(), new byte[0]);

        Parity.Pair pair = parity.requestEach(
                method,
                javaPath,
                resolve(headers, first.java()),
                nestjsPath,
                resolve(headers, first.nestjs()),
                new byte[0]);

        assertSameSend(pair, first);
    }

    static List<Map.Entry<String, String>> resolve(List<Map.Entry<String, String>> headers, Parity.Result previous) {
        return headers.stream()
                .map(header -> Map.entry(
                        header.getKey(),
                        header.getValue()
                                .replace("{etag}", previous.header("etag"))
                                .replace("{lm}", previous.header("last-modified"))))
                .toList();
    }

    @ParameterizedTest
    @MethodSource("repeatedHeaderCases")
    void publicFileRepeatedHeaders(List<Map.Entry<String, String>> headers) {
        if (sendFiles == null) {
            sendFiles = uploadPair("image");
        }
        List<JsonNode> files = sendFiles;
        String javaPath = "/api/v1/files/" + storedName(files.get(0));
        String nestjsPath = "/api/v1/files/" + storedName(files.get(1));
        Parity.Pair first = parity.requestEach("GET", javaPath, Map.of(), nestjsPath, Map.of(), new byte[0]);

        Parity.Pair pair = parity.requestEachHeaders(
                "GET", javaPath, resolve(headers, first.java()), nestjsPath, resolve(headers, first.nestjs()));

        assertSameSend(pair, first);
    }

    static void assertSameSend(Parity.Pair pair, Parity.Pair first) {
        String detail = pair.java().status() + " " + new String(pair.java().body(), StandardCharsets.UTF_8) + "\n"
                + pair.nestjs().status() + " " + new String(pair.nestjs().body(), StandardCharsets.UTF_8);
        assertThat(pair.java().status()).as(detail).isEqualTo(pair.nestjs().status());
        assertThat(pair.java().body()).as(detail).isEqualTo(pair.nestjs().body());
        for (String header : SEND_HEADERS) {
            assertThat(pair.java().header(header))
                    .as(header)
                    .isEqualTo(pair.nestjs().header(header));
        }
        assertThat(pair.java().header("etag")).isEqualTo(first.java().header("etag"));
        assertThat(pair.nestjs().header("etag")).isEqualTo(first.nestjs().header("etag"));
        assertThat(pair.java().header("last-modified")).isNotNull();
        assertThat(pair.nestjs().header("last-modified")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/files/nope.png",
                "/api/v1/files/upload",
                "/api/v1/files/private/abc",
                "/api/v1/files/private/00000000-0000-4000-8000-000000000000"
            })
    void downloadErrors(String url) {
        assertSame(parity.request("GET", url));
        assertSame(authed("GET", url, user.tokens(), Map.of(), new byte[0]));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "abc",
                "00000000-0000-9000-0000-000000000000",
                "%7B00000000-0000-4000-8000-000000000000%7D",
                "00000000-0000-0000-0000-000000000000",
                "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"
            })
    void privateFileIdValidation(String fileId) {
        assertSame(authed("GET", "/api/v1/files/private/" + fileId, user.tokens(), Map.of(), new byte[0]));
    }

    @Test
    void privateFileAccess() {
        List<JsonNode> files = uploadPair("private-image");
        Map<List<String>, Integer> viewers = new LinkedHashMap<>();
        viewers.put(user.tokens(), 200);
        viewers.put(admin, 403);
        for (Map.Entry<List<String>, Integer> viewer : viewers.entrySet()) {
            Parity.Pair pair = parity.requestEach(
                    "GET",
                    "/api/v1/files/private/" + files.get(0).path("id").asString(),
                    Map.of("Authorization", "Bearer " + viewer.getKey().get(0)),
                    "/api/v1/files/private/" + files.get(1).path("id").asString(),
                    Map.of("Authorization", "Bearer " + viewer.getKey().get(1)),
                    new byte[0]);
            assertThat(pair.java().status()).isEqualTo(viewer.getValue());
            assertThat(pair.nestjs().status()).isEqualTo(viewer.getValue());
            assertThat(pair.java().header("content-type"))
                    .isEqualTo(pair.nestjs().header("content-type"));
            assertThat(pair.java().body()).isEqualTo(pair.nestjs().body());
        }
    }
}
