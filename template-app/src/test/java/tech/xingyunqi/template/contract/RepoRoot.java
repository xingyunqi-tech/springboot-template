package tech.xingyunqi.template.contract;

import java.nio.file.Files;
import java.nio.file.Path;

final class RepoRoot {

    private RepoRoot() {}

    static Path path() {
        String configured = System.getProperty("repo.root");
        Path start = configured != null ? Path.of(configured) : Path.of("").toAbsolutePath();
        for (Path current = start.toAbsolutePath(); current != null; current = current.getParent()) {
            if (Files.exists(current.resolve("openapi/nestjs-baseline.json"))) {
                return current;
            }
        }
        throw new IllegalStateException("找不到仓库根目录：" + start);
    }
}
