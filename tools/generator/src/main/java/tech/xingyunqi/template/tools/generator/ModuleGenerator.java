package tech.xingyunqi.template.tools.generator;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 按 OpenAPI 标签生成模块骨架：{@code scope} 写入 scope.yaml，{@code scaffold} 按生成的 {@code <Tag>Api} 写出 controller 桩与分层文件。 */
public final class ModuleGenerator {

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*");
    static final String BASE_PACKAGE = "tech.xingyunqi.template";
    static final String API_PACKAGE = BASE_PACKAGE + ".contract.api";
    static final List<String> HTTP_METHODS = List.of("get", "post", "put", "patch", "delete");

    /** 生成失败：参数错误、标签不存在、目标文件已存在等。 */
    static final class GeneratorException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        GeneratorException(String message) {
            super(message);
        }
    }

    /** 基线中该标签下的一个 operation；{@code secured} 为基线声明了 security。 */
    record Operation(String id, String method, String path, boolean secured) {}

    private final Path root;
    private final PrintStream out;

    ModuleGenerator(Path root, PrintStream out) {
        this.root = root;
        this.out = out;
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            throw new GeneratorException("usage: ModuleGenerator <root> scope|scaffold <name> <tag>");
        }
        ModuleGenerator generator = new ModuleGenerator(Path.of(args[0]), System.out);
        switch (args[1]) {
            case "scope" -> generator.scope(args[2], args[3]);
            case "scaffold" -> generator.scaffold(args[2], args[3]);
            default -> throw new GeneratorException("unknown step " + args[1]);
        }
    }

    /** 校验参数与目标目录，把该标签缺少的 operationId 追加到 scope.yaml 的 {@code operations} 末尾；全部已列出时拒绝。 */
    void scope(String name, String tag) {
        validate(name, tag);
        List<Operation> operations = operations(tag);
        Path moduleDir = moduleDir(name);
        if (Files.exists(moduleDir)) {
            throw new GeneratorException("module already exists: " + root.relativize(moduleDir));
        }
        rejectImplemented(tag);
        Path scope = root.resolve("openapi/scope.yaml");
        String current = read(scope);
        List<String> missing =
                missingFromScope(current, operations.stream().map(Operation::id).toList());
        if (missing.isEmpty()) {
            throw new GeneratorException("all operations of " + tag + " are already in openapi/scope.yaml");
        }
        write(scope, addToScope(current, missing));
        out.println("Added " + missing.size() + " operations of " + tag + " to openapi/scope.yaml");
    }

    /** 按已生成的 {@code <Tag>Api} 写出模块文件，不覆盖已有文件。 */
    void scaffold(String name, String tag) {
        validate(name, tag);
        List<Operation> operations = operations(tag);
        rejectImplemented(tag);
        String type = typeName(tag);
        Class<?> api = loadApi(type + "Api");
        Map<String, Operation> byId = new LinkedHashMap<>();
        operations.forEach(operation -> byId.put(operation.id(), operation));
        Stubs stubs = Stubs.of(api, byId);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("package", BASE_PACKAGE + ".modules." + name);
        model.put("type", type);
        model.put("api", api.getSimpleName());
        model.put("tag", tag);
        model.put("imports", stubs.imports());
        model.put("classAuth", stubs.classAuth() == null ? "" : stubs.classAuth());
        model.put(
                "methods", stubs.methods().stream().map(Stubs.StubMethod::model).toList());

        Path moduleDir = moduleDir(name);
        Map<Path, String> files = new LinkedHashMap<>();
        files.put(moduleDir.resolve("web/" + type + "Controller.java"), "controller.java.ftl");
        files.put(moduleDir.resolve(type + "Service.java"), "service.java.ftl");
        files.put(moduleDir.resolve(type + "Repository.java"), "repository.java.ftl");
        files.put(moduleDir.resolve("persistence/" + type + "Mapper.java"), "mapper.java.ftl");
        files.put(moduleDir.resolve("persistence/" + type + "RepositoryImpl.java"), "repository-impl.java.ftl");
        for (Path file : files.keySet()) {
            if (Files.exists(file)) {
                throw new GeneratorException("file already exists: " + root.relativize(file));
            }
        }
        files.forEach((file, template) -> write(file, render(template, model)));

        out.println("Generated the " + tag + " module in " + root.relativize(moduleDir) + ". Next:");
        out.println("  1. declare permissions in openapi/scope.yaml and on the controller (@PermissionsGuarded,"
                + " @RequirePermissions) as in the nestjs controller:");
        for (Operation operation : operations) {
            out.println("       " + operation.id() + "  " + operation.method().toUpperCase(Locale.ROOT) + " "
                    + operation.path());
        }
        out.println("  2. implement the controller methods, the service and the mapper queries");
    }

    static void validate(String name, String tag) {
        if (!NAME.matcher(name).matches() || tag.isBlank()) {
            throw new GeneratorException(
                    "usage: make module NAME=<package, e.g. orders> TAG=<OpenAPI tag, e.g. Orders>");
        }
        if (SourceVersion.isKeyword(name)) {
            throw new GeneratorException("NAME must not be a Java keyword or reserved word: " + name);
        }
    }

    /** 已有 controller 实现该标签的 {@code <Tag>Api} 时拒绝。 */
    void rejectImplemented(String tag) {
        String api = typeName(tag) + "Api";
        Pattern implementsApi = Pattern.compile("\\bimplements\\b[^{]*\\b" + api + "\\b");
        Path sources = root.resolve("template-app/src/main/java");
        if (!Files.isDirectory(sources)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> implementsApi.matcher(read(file)).find())
                    .findFirst()
                    .ifPresent(file -> {
                        throw new GeneratorException(api + " is already implemented by " + root.relativize(file));
                    });
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 基线中带该标签的 operation，按 operationId 排序。 */
    List<Operation> operations(String tag) {
        JsonNode baseline = JsonMapper.builder().build().readTree(read(root.resolve("openapi/nestjs-baseline.json")));
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : baseline.path("paths").properties()) {
            for (String method : HTTP_METHODS) {
                JsonNode node = path.getValue().get(method);
                if (node == null || node.path("tags").valueStream().noneMatch(t -> tag.equals(t.asString()))) {
                    continue;
                }
                String id = node.path("operationId").asString("");
                if (id.isEmpty()) {
                    throw new GeneratorException("operation without operationId: " + method + " " + path.getKey());
                }
                operations.add(new Operation(
                        id, method, path.getKey(), !node.path("security").isEmpty()));
            }
        }
        if (operations.isEmpty()) {
            throw new GeneratorException("no operation has the tag " + tag);
        }
        operations.sort(Comparator.comparing(Operation::id));
        return operations;
    }

    /** {@code ids} 中尚未列在 scope.yaml 里的（含带权限声明的写法）。 */
    static List<String> missingFromScope(String scope, List<String> ids) {
        List<String> listed = scope.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2).strip())
                .map(line -> line.startsWith("operationId:")
                        ? line.substring("operationId:".length()).strip()
                        : line)
                .toList();
        return ids.stream().filter(id -> !listed.contains(id)).toList();
    }

    /** 在 {@code operations:} 列表末尾追加未列出的 operationId；已列出的跳过。 */
    static String addToScope(String scope, List<String> ids) {
        List<String> lines = new ArrayList<>(scope.lines().toList());
        int start = lines.indexOf("operations:");
        if (start < 0) {
            throw new GeneratorException("openapi/scope.yaml has no operations list");
        }
        int end = start + 1;
        while (end < lines.size() && (lines.get(end).isEmpty() || lines.get(end).startsWith(" "))) {
            end++;
        }
        while (end > start + 1 && lines.get(end - 1).isBlank()) {
            end--;
        }
        List<String> added =
                missingFromScope(scope, ids).stream().map(id -> "  - " + id).toList();
        lines.addAll(end, added);
        return String.join("\n", lines) + "\n";
    }

    /** openapi-generator {@code useTags}：标签按非字母数字切分后各段首字母大写拼接。 */
    static String typeName(String tag) {
        StringBuilder name = new StringBuilder();
        for (String part : tag.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return name.toString();
    }

    private Path moduleDir(String name) {
        return root.resolve("template-app/src/main/java/" + BASE_PACKAGE.replace('.', '/') + "/modules/" + name);
    }

    private static Class<?> loadApi(String simpleName) {
        try {
            return Class.forName(API_PACKAGE + "." + simpleName);
        } catch (ClassNotFoundException e) {
            throw new GeneratorException(
                    simpleName + " is not generated; run the scope step and rebuild template-contract first");
        }
    }

    private static String render(String template, Map<String, Object> model) {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_34);
        configuration.setClassForTemplateLoading(ModuleGenerator.class, "/templates/module");
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        try {
            Template loaded = configuration.getTemplate(template);
            StringWriter writer = new StringWriter();
            loaded.process(model, writer);
            return writer.toString();
        } catch (IOException | TemplateException e) {
            throw new IllegalStateException("cannot render " + template, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
