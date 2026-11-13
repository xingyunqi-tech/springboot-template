package tech.xingyunqi.template.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tech.xingyunqi.contracttest.ContractTestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContractTestController.class)
class HttpContractIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    MockHttpServletResponse send(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder).andReturn().getResponse();
    }

    MockHttpServletResponse send(String method, String uri, Map<String, String> headers, byte @Nullable [] body)
            throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), URI.create(uri));
        headers.forEach(builder::header);
        if (body != null) {
            builder.content(body);
        }
        return send(builder);
    }

    MockHttpServletResponse get(String uri, Map<String, String> headers) throws Exception {
        return send("GET", uri, headers, null);
    }

    MockHttpServletResponse get(String uri) throws Exception {
        return get(uri, Map.of());
    }

    MockHttpServletResponse postJson(String uri, String body, Map<String, String> headers) throws Exception {
        java.util.Map<String, String> all = new java.util.HashMap<>(headers);
        all.putIfAbsent("Content-Type", "application/json");
        return send("POST", uri, all, body.getBytes(StandardCharsets.UTF_8));
    }

    static JsonNode body(MockHttpServletResponse response) throws Exception {
        assertThat(response.getHeader("Content-Type")).isEqualTo("application/json; charset=utf-8");
        return JSON.readTree(response.getContentAsByteArray());
    }

    static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Nested
    class SuccessEnvelope {

        @Test
        @DisplayName("有返回值的接口包装为信封，字节与 Express 输出一致")
        void wrapsPayload() throws Exception {
            MockHttpServletResponse response = get("/api/v1/test/ok");
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString())
                    .isEqualTo("{\"code\":200,\"message\":\"success\",\"data\":{\"value\":1,\"items\":[1,2]}}");
        }

        @Test
        @DisplayName("POST 默认返回 200")
        void postReturns200() throws Exception {
            assertThat(send("POST", "/api/v1/test/void", Map.of(), null).getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("upload 返回 HTTP 201、体内 code 为 200")
        void uploadStatus201() throws Exception {
            MockHttpServletResponse response = send("POST", "/api/v1/test/upload", Map.of(), null);
            assertThat(response.getStatus()).isEqualTo(201);
            assertThat(body(response).get("code").intValue()).isEqualTo(200);
        }

        @Test
        @DisplayName("void 接口不输出 data 键")
        void voidOmitsData() throws Exception {
            MockHttpServletResponse response = send("POST", "/api/v1/test/void", Map.of(), null);
            assertThat(response.getContentAsString()).isEqualTo("{\"code\":200,\"message\":\"success\"}");
            assertThat(response.getHeader("Content-Type")).isEqualTo("application/json; charset=utf-8");
        }

        @Test
        @DisplayName("已是信封的返回值原样输出")
        void existingEnvelopePassesThrough() throws Exception {
            assertThat(body(get("/api/v1/test/envelope")))
                    .isEqualTo(json("{\"code\":201,\"message\":\"custom\",\"data\":[1]}"));
        }

        @Test
        @DisplayName("返回 null 时 data 为 null")
        void nullResultIsNullData() throws Exception {
            assertThat(get("/api/v1/test/null").getContentAsString())
                    .isEqualTo("{\"code\":200,\"message\":\"success\",\"data\":null}");
        }

        @Test
        @DisplayName("数字与时间按 JSON.stringify 输出")
        void jsonStringifySemantics() throws Exception {
            assertThat(get("/api/v1/test/numbers").getContentAsString())
                    .isEqualTo("{\"code\":200,\"message\":\"success\",\"data\":{\"integral\":10,\"negativeZero\":0,"
                            + "\"large\":123456789012345680000,\"small\":0.000001,\"nan\":null,"
                            + "\"at\":\"2024-01-01T00:00:00.123Z\"}}");
        }

        @Test
        @DisplayName("@RawResponse 不包装信封")
        void wrapDisabled() throws Exception {
            assertThat(JSON.readTree(get("/api/v1/test/raw").getContentAsByteArray()))
                    .isEqualTo(json("{\"plain\":true}"));
        }
    }

    static Stream<Arguments> errorBodies() {
        return Stream.of(
                Arguments.of("session", "en", 404, "Session not found", "null"),
                Arguments.of("session", "zh", 404, "会话不存在", "null"),
                Arguments.of("forbidden", "zh", 403, "Missing permissions: user:read, user:list", "null"),
                Arguments.of("field", "en", 422, "Validation failed", "{\"email\":\"Not found\"}"),
                Arguments.of("field", "zh", 422, "数据验证失败", "{\"email\":\"资源不存在\"}"),
                Arguments.of(
                        "nested",
                        "en",
                        422,
                        "Validation failed",
                        "{\"phone\":\"Requests are too frequent, please try again later\",\"retryAfter\":42,"
                                + "\"x\":{\"a\":\"notFound\"}}"),
                Arguments.of(
                        "business",
                        "en",
                        409,
                        "This session has ended",
                        "{\"sessionId\":\"s1\",\"remaining\":3,\"errorKey\":\"sessionClosed\"}"),
                Arguments.of("throttle", "zh", 429, "请求过于频繁，请稍后再试", "null"),
                Arguments.of("plain-string", "en", 400, "Bad request", "null"),
                Arguments.of("default-not-found", "zh", 404, "资源不存在", "null"),
                Arguments.of("unique-email", "en", 422, "Validation failed", "{\"email\":\"Email already exists\"}"),
                Arguments.of("unique-favorite", "en", 409, "Already in favorites", "null"),
                Arguments.of("unique-order", "en", 500, "Internal server error", "null"),
                Arguments.of("unique-unknown", "en", 409, "Conflict", "null"),
                Arguments.of("unknown", "zh", 500, "服务器内部错误", "null"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    @DisplayName("各类异常输出 nestjs 错误信封")
    void errorBodies(String name, String language, int status, String message, String data) throws Exception {
        MockHttpServletResponse response = get("/api/v1/test/errors/" + name, Map.of("Accept-Language", language));
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(body(response))
                .isEqualTo(JSON.createObjectNode()
                        .put("code", status)
                        .put("message", message)
                        .set("data", json(data)));
    }

    @Test
    @DisplayName("429 保留 Retry-After 头")
    void throttleKeepsRetryAfter() throws Exception {
        assertThat(get("/api/v1/test/errors/throttle").getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("未知异常的响应仍带 Request ID 与安全头")
    void unknownErrorKeepsOuterHeaders() throws Exception {
        MockHttpServletResponse response = get("/api/v1/test/errors/unknown");
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Nested
    class Validation {

        @Test
        @DisplayName("同一字段所有失败约束按装饰器顺序以逗号拼接")
        void reportsAllConstraintsPerField() throws Exception {
            MockHttpServletResponse response = postJson("/api/v1/test/login", "{}", Map.of());
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(body(response))
                    .isEqualTo(json("{\"code\":422,\"message\":\"Validation failed\",\"data\":{"
                            + "\"email\":\"email should not be empty, email must be an email\","
                            + "\"password\":\"Password must be a valid MD5 hash (32 hexadecimal characters), "
                            + "password should not be empty\"}}"));
        }

        @Test
        @DisplayName("422 的 message 按 Accept-Language 翻译")
        void messageTranslated() throws Exception {
            MockHttpServletResponse response =
                    postJson("/api/v1/test/login", "{\"email\":\"x\"}", Map.of("Accept-Language", "zh-CN"));
            assertThat(body(response).get("message").stringValue()).isEqualTo("数据验证失败");
        }

        @Test
        @DisplayName("执行 @Transform 并剔除未知字段")
        void appliesTransformsAndStripsUnknownFields() throws Exception {
            MockHttpServletResponse response = postJson(
                    "/api/v1/test/login",
                    "{\"email\":\" Admin@Example.COM \",\"password\":\"E10ADC3949BA59ABBE56E057F20F883E\",\"extra\":1}",
                    Map.of());
            assertThat(body(response).get("data"))
                    .isEqualTo(json(
                            "{\"email\":\"admin@example.com\",\"password\":\"e10adc3949ba59abbe56e057f20f883e\"}"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"{bad", "null", "\"x\"", "1"})
        @DisplayName("非法 JSON 或顶层原始值返回 400")
        void invalidJsonIs400(String payload) throws Exception {
            MockHttpServletResponse response = postJson("/api/v1/test/login", payload, Map.of());
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(body(response).get("data").isNull()).isTrue();
        }

        @Test
        @DisplayName("超过 256kb 返回 413 body-parser 原文")
        void bodyOver256kbIs413() throws Exception {
            MockHttpServletResponse response =
                    postJson("/api/v1/test/login", "{\"email\":\"" + "a".repeat(262144) + "\"}", Map.of());
            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(body(response))
                    .isEqualTo(json("{\"code\":413,\"message\":\"request entity too large\",\"data\":null}"));
        }

        @Test
        @DisplayName("非 JSON 请求体按空对象校验")
        void nonJsonBodyValidatedAsEmptyObject() throws Exception {
            MockHttpServletResponse response = send(
                    "POST",
                    "/api/v1/test/login",
                    Map.of("Content-Type", "application/x-www-form-urlencoded"),
                    "email=a%40b.com".getBytes(StandardCharsets.UTF_8));
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(body(response).get("data").propertyNames()).containsExactlyInAnyOrder("email", "password");
        }

        @Test
        @DisplayName("数组请求体按实例校验")
        void arrayBodyValidatedAsInstance() throws Exception {
            MockHttpServletResponse response = postJson("/api/v1/test/login", "[1]", Map.of());
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(body(response).get("data").propertyNames()).containsExactlyInAnyOrder("email", "password");
        }

        @Test
        @DisplayName("只有空白的请求体返回 400")
        void whitespaceOnlyBodyIs400() throws Exception {
            assertThat(postJson("/api/v1/test/login", "   ", Map.of()).getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("空请求体按空对象校验")
        void emptyBodyIsEmptyObject() throws Exception {
            assertThat(postJson("/api/v1/test/login", "", Map.of()).getStatus()).isEqualTo(422);
        }

        @Test
        @DisplayName("忽略 UTF-8 BOM")
        void utf8BomIsIgnored() throws Exception {
            MockHttpServletResponse response = postJson(
                    "/api/v1/test/login",
                    "﻿{\"email\":\"a\"}",
                    Map.of("Content-Type", "application/json; charset=utf-8"));
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(body(response).get("data").get("email").stringValue()).isEqualTo("email must be an email");
        }

        @Test
        @DisplayName("constructor、toString 等原型键被剔除")
        void protoKeysStripped() throws Exception {
            MockHttpServletResponse response = postJson(
                    "/api/v1/test/login",
                    "{\"email\":\"a@b.com\",\"password\":\"e10adc3949ba59abbe56e057f20f883e\","
                            + "\"constructor\":{\"x\":1},\"toString\":\"x\"}",
                    Map.of());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    static Stream<Arguments> invalidJsonRejectedBeforeRouting() {
        return Stream.of(
                Arguments.of("POST", "/api/v1/nope"), Arguments.of("GET", "/"), Arguments.of("PATCH", "/api/health"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    @DisplayName("请求体解析先于路由：非法 JSON 返回 400 而不是 404")
    void invalidJsonRejectedBeforeRouting(String method, String path) throws Exception {
        MockHttpServletResponse response =
                send(method, path, Map.of("Content-Type", "application/json"), "x".getBytes(StandardCharsets.UTF_8));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response).get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("请求体超限先于路由返回 413")
    void oversizedBodyRejectedBeforeRouting() throws Exception {
        assertThat(postJson("/api/v1/nope", "[" + "1,".repeat(140000) + "1]", Map.of())
                        .getStatus())
                .isEqualTo(413);
    }

    @Nested
    class Paths {

        @Test
        @DisplayName("静态段按原始路径匹配，百分号编码不解码")
        void percentEncodedRouteSegmentIsNotDecoded() throws Exception {
            MockHttpServletResponse response = get("/api/%68ealth");
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(body(response).get("message").stringValue()).isEqualTo("Cannot GET /api/%68ealth");
        }

        static Stream<Arguments> paramsAreUriDecoded() {
            return Stream.of(Arguments.of("a%20b", "a b"), Arguments.of("%E4%B8%AD", "中"), Arguments.of("a+b", "a+b"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        @DisplayName("路径参数按 decodeURIComponent 解码")
        void paramsAreUriDecoded(String raw, String decoded) throws Exception {
            assertThat(body(get("/api/v1/test/files/" + raw))
                            .get("data")
                            .get("name")
                            .stringValue())
                    .isEqualTo(decoded);
        }
    }

    @Nested
    class Negotiation {

        @ParameterizedTest
        @ValueSource(strings = {"text/plain", "text/html", "application/xml"})
        @DisplayName("忽略 Accept 头，始终输出 JSON")
        void acceptHeaderIgnored(String accept) throws Exception {
            MockHttpServletResponse response = get("/api/health", Map.of("Accept", accept));
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(body(response).get("code").intValue()).isEqualTo(200);
        }

        @Test
        @DisplayName("返回 String 的接口同样包装为信封")
        void stringResultWrapped() throws Exception {
            assertThat(get("/api/v1/test/text").getContentAsString())
                    .isEqualTo("{\"code\":200,\"message\":\"success\",\"data\":\"plain\"}");
        }

        @Test
        @DisplayName("TRACE 与其它未匹配方法同样返回 404")
        void traceIsNotFound() throws Exception {
            MockHttpServletResponse response = send("TRACE", "/api/x", Map.of(), null);
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(body(response).get("message").stringValue()).isEqualTo("Cannot TRACE /api/x");
        }

        @Test
        @DisplayName("不暴露 Spring Boot 的 /error 端点")
        void errorEndpointNotExposed() throws Exception {
            assertThat(body(get("/error")).get("message").stringValue()).isEqualTo("Cannot GET /error");
        }

        @Test
        @DisplayName("查询参数类型不符时返回 400 而不是 {id: invalidId}")
        void queryTypeMismatchIsBadRequest() throws Exception {
            MockHttpServletResponse response = get("/api/v1/test/page?page=abc");
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(body(response).get("data").isNull()).isTrue();
        }
    }

    @Nested
    class Language {

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "zh--,en|数据验证失败",
                    "zh-,en|数据验证失败",
                    "zh-Hans-,en|Validation failed",
                    "zh-x-y-z,en|Validation failed"
                })
        @DisplayName("语言标签的空 script / region 视为未指定，与 nestjs 实测一致")
        void emptySubtagsIgnored(String header, String expected) throws Exception {
            MockHttpServletResponse response = postJson("/api/v1/test/login", "{}", Map.of("Accept-Language", header));
            assertThat(body(response).get("message").stringValue()).isEqualTo(expected);
        }

        @Test
        @DisplayName("大量 q 值非法的语言标签不影响请求")
        void manyInvalidQualitiesTolerated() throws Exception {
            StringBuilder header = new StringBuilder();
            String[] qualities = {"0.5", "abc", "1", "x", "0.1"};
            for (int i = 0; i < 200; i++) {
                header.append(i == 0 ? "" : ",")
                        .append("x")
                        .append(i)
                        .append(";q=")
                        .append(qualities[i % 5]);
            }
            assertThat(get("/", Map.of("Accept-Language", header.toString())).getStatus())
                    .isEqualTo(200);
        }
    }

    @Nested
    class BodyEncoding {

        @Test
        @DisplayName("非 utf- 开头的 charset 返回 415")
        void unsupportedCharsetIs415() throws Exception {
            MockHttpServletResponse response =
                    postJson("/api/v1/nope", "{}", Map.of("Content-Type", "application/json; charset=latin1"));
            assertThat(response.getStatus()).isEqualTo(415);
            assertThat(body(response).get("message").stringValue()).isEqualTo("unsupported charset \"LATIN1\"");
        }

        @Test
        @DisplayName("按 charset 解码 utf-16le 请求体")
        void utf16BodyDecoded() throws Exception {
            MockHttpServletResponse response = send(
                    "POST",
                    "/api/v1/nope",
                    Map.of("Content-Type", "application/json; charset=utf-16le"),
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            assertThat(response.getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("gzip 请求体先解压再解析")
        void gzipBodyInflated() throws Exception {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) {
                gzip.write("{\"email\":\"a\"}".getBytes(StandardCharsets.UTF_8));
            }
            MockHttpServletResponse response = send(
                    "POST",
                    "/api/v1/test/login",
                    Map.of("Content-Type", "application/json", "Content-Encoding", "gzip"),
                    bytes.toByteArray());
            assertThat(body(response).get("data").get("email").stringValue()).isEqualTo("email must be an email");
        }

        @Test
        @DisplayName("不支持的 Content-Encoding 返回 415")
        void unsupportedEncodingIs415() throws Exception {
            MockHttpServletResponse response = postJson("/api/v1/nope", "{}", Map.of("Content-Encoding", "br2"));
            assertThat(response.getStatus()).isEqualTo(415);
            assertThat(body(response).get("message").stringValue()).isEqualTo("unsupported content encoding \"br2\"");
        }

        @Test
        @DisplayName("超长数字与深层嵌套的合法 JSON 不被解析器拒绝")
        void largeNumbersAndDeepNestingAccepted() throws Exception {
            assertThat(postJson("/api/v1/nope", "[" + "1".repeat(1500) + "]", Map.of())
                            .getStatus())
                    .isEqualTo(404);
            assertThat(postJson("/api/v1/nope", "[".repeat(1200) + "]".repeat(1200), Map.of())
                            .getStatus())
                    .isEqualTo(404);
        }
    }

    static Stream<Arguments> invalidIdIs400() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String raw : new String[] {"abc", "0", "-1", "1.5", "2147483648", "%EF%BC%91"}) {
            cases.add(Arguments.of(raw, "en", "Bad request", "Invalid id"));
            cases.add(Arguments.of(raw, "zh", "请求参数错误", "ID 格式不正确"));
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    @DisplayName("非法 :id 返回 400 {id: invalidId}")
    void invalidIdIs400(String raw, String language, String message, String detail) throws Exception {
        MockHttpServletResponse response = get("/api/v1/test/items/" + raw, Map.of("Accept-Language", language));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response))
                .isEqualTo(JSON.createObjectNode()
                        .put("code", 400)
                        .put("message", message)
                        .set("data", JSON.createObjectNode().put("id", detail)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1:1", "007:7", "2147483647:2147483647"})
    @DisplayName("合法 :id 解析为整数")
    void validId(String pair) throws Exception {
        String[] parts = pair.split(":");
        assertThat(body(get("/api/v1/test/items/" + parts[0]))
                        .get("data")
                        .get("id")
                        .intValue())
                .isEqualTo(Integer.parseInt(parts[1]));
    }

    @Nested
    class Routing {

        @Test
        @DisplayName("未知路由的 message 含原始查询串")
        void unknownRouteIncludesQueryString() throws Exception {
            MockHttpServletResponse response = get("/api/v1/nope?a=1%20b&c");
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(body(response))
                    .isEqualTo(json("{\"code\":404,\"message\":\"Cannot GET /api/v1/nope?a=1%20b&c\",\"data\":null}"));
        }

        @Test
        @DisplayName("方法不符返回 404 且不带 Allow 头")
        void methodMismatchIs404WithoutAllow() throws Exception {
            MockHttpServletResponse response = send("PATCH", "/api/health?x=1", Map.of(), null);
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(response.getHeader("Allow")).isNull();
            assertThat(body(response).get("message").stringValue()).isEqualTo("Cannot PATCH /api/health?x=1");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/api/health/", "/API/Health", "/Api/v1/Test/OK/"})
        @DisplayName("尾斜杠宽容、路径不区分大小写")
        void trailingSlashAndCaseInsensitive(String path) throws Exception {
            assertThat(get(path).getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("双尾斜杠不匹配")
        void doubleTrailingSlashNotMatched() throws Exception {
            assertThat(get("/api/health//").getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("HEAD 命中 GET 路由")
        void headOnGetRoute() throws Exception {
            assertThat(send("HEAD", "/api/health", Map.of(), null).getStatus()).isEqualTo(200);
        }
    }

    static Stream<String> invalidRequestIds() {
        return Stream.of("bad id!", "", "x".repeat(129));
    }

    @Nested
    class RequestId {

        @Test
        @DisplayName("沿用合法的入站 X-Request-Id")
        void echoesValidId() throws Exception {
            assertThat(get("/", Map.of("X-Request-Id", "abc.1:2-3")).getHeader("X-Request-Id"))
                    .isEqualTo("abc.1:2-3");
        }

        @ParameterizedTest
        @MethodSource("tech.xingyunqi.template.http.HttpContractIT#invalidRequestIds")
        @DisplayName("非法或缺失时生成 UUID")
        void generatesUuid(String incoming) throws Exception {
            String generated = get("/", Map.of("X-Request-Id", incoming)).getHeader("X-Request-Id");
            assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        }

        @Test
        @DisplayName("多个同名 X-Request-Id 头时生成 UUID")
        void multipleHeadersGenerateUuid() throws Exception {
            String generated = send(request(HttpMethod.GET, URI.create("/")).header("X-Request-Id", "a", "b"))
                    .getHeader("X-Request-Id");
            assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        }
    }

    @Nested
    class Cors {

        @Test
        @DisplayName("非生产环境回显来源")
        void developmentReflectsOrigin() throws Exception {
            MockHttpServletResponse response = get("/", Map.of("Origin", "http://a.com"));
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://a.com");
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
            assertThat(response.getHeader("Vary")).isEqualTo("Origin");
            assertThat(response.getHeader("Access-Control-Expose-Headers"))
                    .isEqualTo("X-Request-Id,Retry-After,X-RateLimit-Limit,X-RateLimit-Remaining,X-RateLimit-Reset");
        }

        @Test
        @DisplayName("无 Origin 时不输出 Allow-Origin")
        void noOriginHeader() throws Exception {
            MockHttpServletResponse response = get("/");
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            assertThat(response.getHeader("Vary")).isEqualTo("Origin");
        }

        @Test
        @DisplayName("任意路径的 OPTIONS 返回 204 空体")
        void preflight() throws Exception {
            MockHttpServletResponse response = send(
                    "OPTIONS",
                    "/api/v1/auth/me",
                    Map.of(
                            "Origin", "http://a.com",
                            "Access-Control-Request-Method", "POST",
                            "Access-Control-Request-Headers", "x-foo,content-type"),
                    null);
            assertThat(response.getStatus()).isEqualTo(204);
            assertThat(response.getContentAsByteArray()).isEmpty();
            assertThat(response.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET,HEAD,PUT,PATCH,POST,DELETE");
            assertThat(response.getHeader("Access-Control-Allow-Headers")).isEqualTo("x-foo,content-type");
            assertThat(response.getHeader("Vary")).isEqualTo("Origin, Access-Control-Request-Headers");
            assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        }
    }

    @Nested
    class SecurityHeaders {

        @Test
        @DisplayName("与 nestjs helmet 输出一致，HSTS 在所有环境发送")
        void matchesNestjsHelmet() throws Exception {
            MockHttpServletResponse response = get("/");
            assertThat(response.getHeader("Content-Security-Policy")).startsWith("default-src 'self';base-uri 'self';");
            assertThat(response.getHeader("Strict-Transport-Security"))
                    .isEqualTo("max-age=31536000; includeSubDomains");
            assertThat(response.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("cross-origin");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
            assertThat(response.getHeader("X-XSS-Protection")).isEqualTo("0");
            assertThat(response.getHeader("Content-Language")).isNull();
        }

        @Test
        @DisplayName("文档页允许加载 CDN 脚本")
        void docsPageAllowsCdnScript() throws Exception {
            assertThat(get("/docs").getHeader("Content-Security-Policy")).contains("https://cdn.jsdelivr.net");
        }
    }
}
