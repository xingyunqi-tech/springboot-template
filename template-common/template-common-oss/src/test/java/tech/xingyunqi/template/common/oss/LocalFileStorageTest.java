package tech.xingyunqi.template.common.oss;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("库中 path 只取最后一段定位磁盘文件，删除不存在的文件不报错")
    void localFiles() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(root);
        Files.writeString(root.resolve("a.png"), "x");

        assertThat(storage.localFile("/api/v1/files/a.png")).isEqualTo(root.resolve("a.png"));
        storage.delete("/api/v1/files/a.png");
        storage.delete("/api/v1/files/a.png");

        assertThat(root.resolve("a.png")).doesNotExist();
    }
}
