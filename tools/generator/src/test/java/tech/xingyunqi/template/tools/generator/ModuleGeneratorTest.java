package tech.xingyunqi.template.tools.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleGeneratorTest {

    static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    static final String MODULES = "template-app/src/main/java/tech/xingyunqi/template/modules/";

    @TempDir
    Path root;

    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    ModuleGenerator generator;

    @BeforeEach
    void copyFixtures() throws IOException {
        for (String file : List.of("openapi/nestjs-baseline.json", "openapi/scope.yaml")) {
            Files.createDirectories(root.resolve(file).getParent());
            Files.copy(REPO.resolve(file), root.resolve(file));
        }
        generator = new ModuleGenerator(root, new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative));
    }

    @Test
    @DisplayName("scope：把标签下缺少的 operationId 按字母序追加到 operations 列表末尾，全部已列出时拒绝")
    void scopeAppendsOperations() throws IOException {
        generator.scope("orders", "Orders");

        String scope = read("openapi/scope.yaml");
        assertThat(scope)
                .contains("  - cancelOrder\n  - createOrder\n  - getOrder\n  - getOrders\nundocumentedOperations:");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Added 4 operations of Orders");

        assertThatThrownBy(() -> generator.scope("orders", "Orders"))
                .isInstanceOf(ModuleGenerator.GeneratorException.class)
                .hasMessageContaining("all operations of Orders are already in openapi/scope.yaml");
        assertThat(read("openapi/scope.yaml")).isEqualTo(scope);
    }

    @Test
    @DisplayName("scope：只追加尚未列出的 operationId，并打印实际追加的数量")
    void scopeReportsAppendedCount() throws IOException {
        Path scope = root.resolve("openapi/scope.yaml");
        Files.writeString(scope, ModuleGenerator.addToScope(read("openapi/scope.yaml"), List.of("getOrder")));

        generator.scope("orders", "Orders");

        assertThat(read("openapi/scope.yaml").split("- getOrder\n", -1)).hasSize(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Added 3 operations of Orders");
    }

    @Test
    @DisplayName("已有 controller 实现该标签的接口时 scope 与 scaffold 都拒绝")
    void rejectsImplementedTag() throws IOException {
        Path controller = root.resolve(MODULES + "shop/web/ShopController.java");
        Files.createDirectories(controller.getParent());
        Files.writeString(controller, "class ShopController\n        implements OrdersApi, Other {\n}\n");
        String before = read("openapi/scope.yaml");

        assertThatThrownBy(() -> generator.scope("orders", "Orders"))
                .isInstanceOf(ModuleGenerator.GeneratorException.class)
                .hasMessageContaining(
                        "OrdersApi is already implemented by " + MODULES + "shop/web/ShopController.java");
        assertThatThrownBy(() -> generator.scaffold("orders", "Orders"))
                .isInstanceOf(ModuleGenerator.GeneratorException.class)
                .hasMessageContaining("OrdersApi is already implemented");
        assertThat(read("openapi/scope.yaml")).isEqualTo(before);
    }

    @Test
    @DisplayName("NAME 为 Java 关键字或保留字时拒绝")
    void rejectsKeywordName() {
        for (String name : List.of("class", "new", "goto", "const", "true", "null")) {
            assertThatThrownBy(() -> generator.scope(name, "Orders"))
                    .isInstanceOf(ModuleGenerator.GeneratorException.class)
                    .hasMessageContaining("Java keyword");
        }
    }

    @Test
    @DisplayName("addToScope：识别带权限声明的写法，列表在文件末尾时追加到末尾")
    void addToScopeText() {
        String scope = "operations:\n  - a\n  - operationId: b\n    permissions: [x:y]\n\n";

        assertThat(ModuleGenerator.addToScope(scope, List.of("b", "c", "a")))
                .isEqualTo("operations:\n  - a\n  - operationId: b\n    permissions: [x:y]\n  - c\n\n");
        assertThatThrownBy(() -> ModuleGenerator.addToScope("other: []\n", List.of("a")))
                .isInstanceOf(ModuleGenerator.GeneratorException.class);
    }

    @Test
    @DisplayName("参数非法、标签不存在或模块目录已存在时报错且不修改 scope.yaml")
    void rejectsInvalidInput() throws IOException {
        String before = read("openapi/scope.yaml");

        assertThatThrownBy(() -> generator.scope("Bad-Name", "Orders"))
                .isInstanceOf(ModuleGenerator.GeneratorException.class)
                .hasMessageContaining("usage");
        assertThatThrownBy(() -> generator.scope("orders", " ")).isInstanceOf(ModuleGenerator.GeneratorException.class);
        assertThatThrownBy(() -> generator.scope("nope", "Nope"))
                .isInstanceOf(ModuleGenerator.GeneratorException.class)
                .hasMessageContaining("no operation has the tag Nope");
        Files.createDirectories(root.resolve(MODULES + "orders"));
        assertThatThrownBy(() -> generator.scope("orders", "Orders"))
                .isInstanceOf(ModuleGenerator.GeneratorException.class)
                .hasMessageContaining("module already exists");

        assertThat(read("openapi/scope.yaml")).isEqualTo(before);
    }

    @Test
    @DisplayName("标签名按 openapi-generator 规则转为类型名")
    void typeName() {
        assertThat(ModuleGenerator.typeName("Orders")).isEqualTo("Orders");
        assertThat(ModuleGenerator.typeName("Admin App Profile")).isEqualTo("AdminAppProfile");
        assertThat(ModuleGenerator.typeName("Wechat-Pay 2")).isEqualTo("WechatPay2");
    }

    @Test
    @DisplayName("scaffold：controller 实现生成接口，方法抛 501，鉴权按基线 security 逐个声明；生成 service、repository 与 mapper")
    void scaffoldMixedAuth() throws IOException {
        generator.scaffold("config", "Config");

        String controller = read(MODULES + "config/web/ConfigController.java");
        assertThat(controller)
                .contains("package tech.xingyunqi.template.modules.config.web;")
                .contains("import tech.xingyunqi.template.contract.api.ConfigApi;")
                .contains("import tech.xingyunqi.template.contract.model.UpsertConfigDto;")
                .contains("import org.jspecify.annotations.Nullable;")
                .contains("@RestController\nclass ConfigController implements ConfigApi {")
                .contains("    @Override\n    @PublicEndpoint\n"
                        + "    public Config getConfig(String key, @Nullable Object acceptLanguage) {\n"
                        + "        throw notImplemented();")
                .contains("    @AuthenticatedOnly\n    public Config upsertConfig(String key, UpsertConfigDto"
                        + " upsertConfigDto, @Nullable Object acceptLanguage) {")
                .contains("HttpStatusException.of(501, null)");
        assertThat(read(MODULES + "config/ConfigService.java")).contains("final ConfigRepository repository;");
        assertThat(read(MODULES + "config/ConfigRepository.java")).contains("public interface ConfigRepository {}");
        assertThat(read(MODULES + "config/persistence/ConfigMapper.java")).contains("@Mapper");
        assertThat(read(MODULES + "config/persistence/ConfigRepositoryImpl.java"))
                .contains("class ConfigRepositoryImpl implements ConfigRepository {");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("upsertConfig  PUT /api/v1/config/{key}");

        assertThatThrownBy(() -> generator.scaffold("config", "Config"))
                .isInstanceOf(ModuleGenerator.GeneratorException.class)
                .hasMessageContaining("ConfigApi is already implemented");
        Files.delete(root.resolve(MODULES + "config/web/ConfigController.java"));
        assertThatThrownBy(() -> generator.scaffold("config", "Config"))
                .isInstanceOf(ModuleGenerator.GeneratorException.class)
                .hasMessageContaining("file already exists");
    }

    @Test
    @DisplayName("scaffold：所有方法鉴权相同时只在类上声明，泛型返回值带 import")
    void scaffoldUniformAuth() throws IOException {
        generator.scaffold("regions", "Regions");

        String controller = read(MODULES + "regions/web/RegionsController.java");
        assertThat(controller)
                .contains("@RestController\n@PublicEndpoint\nclass RegionsController implements RegionsApi {")
                .contains("import java.util.List;")
                .contains("    @Override\n    public List<Region> getProvinces(@Nullable Object acceptLanguage) {")
                .doesNotContain("    @PublicEndpoint");
    }

    @Test
    @DisplayName("scaffold：接口未生成时提示先执行 scope 并重新生成契约")
    void scaffoldRequiresGeneratedApi() {
        assertThatThrownBy(() -> generator.scaffold("orders", "Orders"))
                .isInstanceOf(ModuleGenerator.GeneratorException.class)
                .hasMessageContaining("OrdersApi is not generated");
    }

    @Test
    @DisplayName("main 校验参数个数与步骤名")
    void mainArguments() {
        assertThatThrownBy(() -> ModuleGenerator.main(new String[] {root.toString()}))
                .isInstanceOf(ModuleGenerator.GeneratorException.class);
        assertThatThrownBy(() -> ModuleGenerator.main(new String[] {root.toString(), "other", "orders", "Orders"}))
                .hasMessageContaining("unknown step");
    }
}
