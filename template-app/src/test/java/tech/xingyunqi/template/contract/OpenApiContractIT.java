package tech.xingyunqi.template.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tech.xingyunqi.template.common.security.route.RouteAuth;
import tech.xingyunqi.template.common.web.docs.DocsController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

@Tag("contract")
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractIT {

    static final Path ROOT = RepoRoot.path();
    static final Path CONTRACT_DOC = ROOT.resolve("docs/contract.md");
    static final String MANUAL_HEADING = "## 与 nestjs 的行为差异";
    static final List<String> HTTP_METHODS = List.of("get", "post", "put", "patch", "delete");
    static final List<String> SHARED_ARTIFACTS = List.of(
            "openapi/SOURCE",
            "openapi/nestjs-baseline.json",
            "openapi/validation.json",
            "openapi/nest_handlers.json",
            "db/nestjs-schema.sql",
            "tests/contract/fixtures/validation-cases.json",
            "scripts/openapi-sync.sh",
            "scripts/schema_snapshot.py",
            "scripts/gen_validation_cases.py",
            "scripts/gen_nest_handlers.py",
            "scripts/nestjs/validation-cases.ts",
            "scripts/nestjs/nestjs.compose.yaml");

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNode BASELINE = read("openapi/nestjs-baseline.json");
    static final JsonNode VALIDATION = read("openapi/validation.json");
    static final Set<String> SCOPE = scope();

    record Operation(String method, String path, String operationId, String tag, JsonNode node) {
        String key() {
            return method + " " + path.toLowerCase(Locale.ROOT);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    static JsonNode read(String relative) {
        try {
            return JSON.readTree(Files.readString(ROOT.resolve(relative)));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static Set<String> scope() {
        try {
            JsonNode root = YAMLMapper.builder().build().readTree(Files.readString(ROOT.resolve("openapi/scope.yaml")));
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode entry : root.path("operations")) {
                ids.add(
                        entry.isString()
                                ? entry.stringValue()
                                : entry.path("operationId").stringValue());
            }
            return ids;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static List<Operation> baselineOperations() {
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : BASELINE.path("paths").properties()) {
            for (String method : HTTP_METHODS) {
                JsonNode node = path.getValue().get(method);
                if (node != null) {
                    String tag = node.path("tags").path(0).stringValue("");
                    operations.add(new Operation(
                            method, path.getKey(), node.path("operationId").stringValue(), tag, node));
                }
            }
        }
        return operations;
    }

    Map<String, HandlerMethod> handlers() {
        Map<String, HandlerMethod> handlers = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                mapping.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                methods = Set.of(RequestMethod.values());
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                for (RequestMethod method : methods) {
                    handlers.put(
                            method.name().toLowerCase(Locale.ROOT) + " " + pattern.toLowerCase(Locale.ROOT),
                            entry.getValue());
                }
            }
        }
        return handlers;
    }

    @Test
    @DisplayName("/docs-json 与基线逐字节一致")
    void docsJsonIsBaselineBytes() throws Exception {
        byte[] body = mvc.perform(get("/docs-json")).andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(Files.readAllBytes(ROOT.resolve("openapi/nestjs-baseline.json")));
    }

    @Test
    @DisplayName("scope.yaml 中每个 operationId 都在基线中")
    void scopeOperationsExistInBaseline() {
        Set<String> baselineIds = new LinkedHashSet<>();
        baselineOperations().forEach(operation -> baselineIds.add(operation.operationId()));
        assertThat(baselineIds).containsAll(SCOPE);
    }

    static Map<String, List<String>> scopePermissions() {
        try {
            JsonNode root = YAMLMapper.builder().build().readTree(Files.readString(ROOT.resolve("openapi/scope.yaml")));
            Map<String, List<String>> permissions = new LinkedHashMap<>();
            for (JsonNode entry : root.path("operations")) {
                if (!entry.isString()) {
                    JsonNode list = entry.has("permissions") ? entry.path("permissions") : entry.path("anyPermissions");
                    permissions.put(
                            entry.path("operationId").stringValue(),
                            list.valueStream().map(JsonNode::stringValue).toList());
                }
            }
            return permissions;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("基线带 security 的 operation 不是公开路由，不带的是公开路由")
    void authenticationMatchesBaselineSecurity() {
        Map<String, HandlerMethod> handlers = handlers();
        List<String> mismatched = new ArrayList<>();
        for (Operation operation : baselineOperations()) {
            HandlerMethod handler = handlers.get(operation.key());
            if (handler == null || !SCOPE.contains(operation.operationId())) {
                continue;
            }
            boolean secured = !operation.node().path("security").isEmpty();
            boolean isPublic = RouteAuth.of(handler).mode() == RouteAuth.Mode.PUBLIC;
            if (secured == isPublic) {
                mismatched.add(operation.operationId());
            }
        }
        assertThat(mismatched).isEmpty();
    }

    @Test
    @DisplayName("权限守卫与 scope.yaml 的 permissions 一致")
    void permissionsMatchScope() {
        Map<String, List<String>> expected = scopePermissions();
        Map<String, HandlerMethod> handlers = handlers();
        Map<String, List<String>> actual = new LinkedHashMap<>();
        for (Operation operation : baselineOperations()) {
            HandlerMethod handler = handlers.get(operation.key());
            if (handler == null || !SCOPE.contains(operation.operationId())) {
                continue;
            }
            RouteAuth route = RouteAuth.of(handler);
            if (route.guarded()) {
                actual.put(operation.operationId(), route.permissions());
            }
        }
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("scope 内每个 operation 都有 handler，scope 外的都没有")
    void handlersMatchScope() {
        Map<String, HandlerMethod> handlers = handlers();
        List<String> missing = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        for (Operation operation : baselineOperations()) {
            boolean handled = handlers.containsKey(operation.key());
            if (SCOPE.contains(operation.operationId()) && !handled) {
                missing.add(operation.key());
            }
            if (!SCOPE.contains(operation.operationId()) && handled) {
                unexpected.add(operation.key());
            }
        }
        assertThat(missing).as("缺少实现").isEmpty();
        assertThat(unexpected).as("scope 外却有实现").isEmpty();
    }

    @Test
    @DisplayName("scope 内 operation 均返回 HTTP 200，只有 upload 为 201")
    void scopeOperationsReturn200() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Operation operation : baselineOperations()) {
            if (!SCOPE.contains(operation.operationId()) || operation.path().contains("{")) {
                continue;
            }
            int status = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                            org.springframework.http.HttpMethod.valueOf(
                                    operation.method().toUpperCase(Locale.ROOT)),
                            operation.path()))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            int expected = operation.operationId().equals("uploadFile") ? 201 : 200;
            if (status != expected && status < 400) {
                wrong.add(operation.key() + " → " + status);
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("没有基线与白名单之外的端点")
    void noEndpointsOutsideContract() {
        Set<String> baselineKeys = new LinkedHashSet<>();
        baselineOperations().forEach(operation -> baselineKeys.add(operation.key()));
        List<String> extra = new ArrayList<>();
        for (Map.Entry<String, HandlerMethod> handler : handlers().entrySet()) {
            boolean whitelisted = handler.getValue().getBeanType() == DocsController.class;
            if (!whitelisted && !baselineKeys.contains(handler.getKey())) {
                extra.add(handler.getKey());
            }
        }
        assertThat(extra).isEmpty();
    }

    @Test
    @DisplayName("生成代码中没有 @ResponseStatus")
    void generatedCodeHasNoResponseStatus() throws IOException {
        Path generated = ROOT.resolve("template-contract/target/generated-sources/openapi");
        assertThat(generated).isDirectory();
        try (Stream<Path> files = Files.walk(generated)) {
            List<Path> offending = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("@ResponseStatus"))
                    .toList();
            assertThat(offending).isEmpty();
        }
    }

    @Test
    @DisplayName("scope 内请求体 DTO 都在 validation.json 中")
    void requestBodiesHaveValidationMetadata() {
        List<String> missing = new ArrayList<>();
        for (Operation operation : baselineOperations()) {
            if (!SCOPE.contains(operation.operationId())) {
                continue;
            }
            String ref = operation
                    .node()
                    .path("requestBody")
                    .path("content")
                    .path("application/json")
                    .path("schema")
                    .path("$ref")
                    .stringValue("");
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            if (!name.isEmpty() && !VALIDATION.path("classes").has(name)) {
                missing.add(operation.operationId() + " → " + name);
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("openapi/SOURCE 记录固定提交与导出环境")
    void sourceRecordsPinnedCommit() throws IOException {
        Map<String, String> source = new LinkedHashMap<>();
        for (String line : Files.readAllLines(ROOT.resolve("openapi/SOURCE"))) {
            String[] kv = line.split("=", 2);
            if (kv.length == 2) {
                source.put(kv[0], kv[1]);
            }
        }
        assertThat(source).containsOnlyKeys("repository", "branch", "commit", "validation_script", "environment");
        assertThat(source.get("commit")).matches("[0-9a-f]{40}");
        assertThat(source.get("validation_script")).matches("[0-9a-f]{40}");
        assertThat(source.get("environment")).isEqualTo("NODE_ENV=development FILE_DRIVER=local");
    }

    @Test
    @DisplayName("共享产物与同级 fastapi-temp 已提交版本逐字节相同")
    void sharedArtifactsMatchFastapi() throws Exception {
        Path fastapi = Path.of(System.getProperty(
                "fastapi.dir", ROOT.resolve("../fastapi-temp").toString()));
        String ref = System.getProperty("fastapi.ref", "HEAD");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(fastapi.resolve(".git")), "同级 fastapi-temp 不存在");
        List<String> different = new ArrayList<>();
        for (String artifact : SHARED_ARTIFACTS) {
            Process git = new ProcessBuilder("git", "-C", fastapi.toString(), "show", ref + ":" + artifact)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] theirs = git.getInputStream().readAllBytes();
            if (git.waitFor() != 0 || !java.util.Arrays.equals(theirs, Files.readAllBytes(ROOT.resolve(artifact)))) {
                different.add(artifact);
            }
        }
        assertThat(different).isEmpty();
    }

    @Test
    @DisplayName("docs/contract.md 与当前实现一致")
    void contractDocIsUpToDate() throws IOException {
        String rendered = renderContractDoc();
        if ("1".equals(System.getenv("UPDATE_CONTRACT_DOC"))) {
            Files.createDirectories(CONTRACT_DOC.getParent());
            Files.writeString(CONTRACT_DOC, rendered, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(CONTRACT_DOC)).isEqualTo(rendered);
    }

    static String renderContractDoc() throws IOException {
        List<String> rows = new ArrayList<>();
        for (Operation operation : baselineOperations()) {
            if (!SCOPE.contains(operation.operationId())) {
                rows.add("| " + operation.method().toUpperCase(Locale.ROOT) + " | `" + operation.path() + "` | "
                        + operation.operationId() + " | " + operation.tag() + " |");
            }
        }
        StringBuilder doc = new StringBuilder();
        doc.append("# 与 nestjs-template 的契约差异\n\n")
                .append("本文件由 `template-app` 的 `OpenApiContractIT` 生成（`UPDATE_CONTRACT_DOC=1 make test-contract`），")
                .append("与实际不符时测试失败；「")
                .append(MANUAL_HEADING.substring(3))
                .append("」一节为手写，生成时原样保留。\n\n")
                .append("## 基线中未实现的接口（")
                .append(rows.size())
                .append(" 个）\n\n请求这些接口返回 404 信封 `Cannot <METHOD> <path>`。\n\n")
                .append("| 方法 | 路径 | operationId | tag |\n|---|---|---|---|\n")
                .append(String.join("\n", rows))
                .append("\n\n## 白名单（实现与基线不同）\n\n")
                .append(String.join("\n", whitelist()))
                .append("\n")
                .append(manualSection());
        return doc.toString();
    }

    static List<String> whitelist() {
        return List.of(
                "- `GET /docs`、`GET /docs-json`：文档端点，不在基线中",
                "- 生成代码的输入经 `openapi/codegen-overlay.yaml` 修正：" + new TreeSet<>(overlayEntries()));
    }

    static Set<String> overlayEntries() {
        try {
            JsonNode overlay = YAMLMapper.builder()
                    .build()
                    .readTree(Files.readString(ROOT.resolve("openapi/codegen-overlay.yaml")));
            Set<String> entries = new LinkedHashSet<>();
            overlay.path("operations").propertyNames().forEach(name -> entries.add("operations." + name));
            overlay.path("schemas").propertyNames().forEach(name -> entries.add("schemas." + name));
            return entries;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static String manualSection() throws IOException {
        if (!Files.exists(CONTRACT_DOC)) {
            return "";
        }
        String current = Files.readString(CONTRACT_DOC);
        int index = current.indexOf("\n" + MANUAL_HEADING + "\n");
        return index < 0 ? "" : current.substring(index);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
