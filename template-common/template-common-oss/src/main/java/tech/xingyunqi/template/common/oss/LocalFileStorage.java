package tech.xingyunqi.template.common.oss;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 文件写在 {@code localRoot}（默认工作目录下 {@code ./files}）。 */
public final class LocalFileStorage implements FileStorage {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;

    public LocalFileStorage(Path root) {
        this.root = root;
    }

    @Override
    public FileDriver driver() {
        return FileDriver.LOCAL;
    }

    @Override
    public Path localRoot() {
        return root;
    }

    @Override
    public Path localFile(String path) {
        int slash = path.lastIndexOf('/');
        return root.resolve(slash < 0 ? path : path.substring(slash + 1));
    }

    @Override
    public void put(String key, Path source, String contentType) throws IOException {
        Files.createDirectories(root);
        Files.move(source, root.resolve(key));
    }

    /** 删除失败时忽略。 */
    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(localFile(path));
        } catch (IOException e) {
            LOG.debug("Failed to delete local file {}", path, e);
        }
    }

    @Override
    public String presignedGetUrl(String key) {
        throw new UnsupportedOperationException("local driver has no signed URLs");
    }

    @Override
    public String presignedPutUrl(String key, long contentLength) {
        throw new UnsupportedOperationException("local driver has no signed URLs");
    }
}
