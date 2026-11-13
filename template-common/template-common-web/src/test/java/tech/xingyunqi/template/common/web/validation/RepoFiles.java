package tech.xingyunqi.template.common.web.validation;

import java.nio.file.Files;
import java.nio.file.Path;

final class RepoFiles {

    static final Path VALIDATION_JSON = resolve("openapi/validation.json");
    static final Path VALIDATION_CASES = resolve("tests/contract/fixtures/validation-cases.json");

    private RepoFiles() {}

    private static Path resolve(String relative) {
        String root = System.getProperty("repo.root");
        if (root != null && !root.isBlank()) {
            return Path.of(root, relative);
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve(relative))) {
                return dir.resolve(relative);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库文件: " + relative);
    }
}
