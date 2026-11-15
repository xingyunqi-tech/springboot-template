package tech.xingyunqi.template.infrastructure.files.upload;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 直接写入 local 驱动的存储目录（multer {@code diskStorage}）。 */
public final class LocalFileSink implements FileSink {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileSink.class);

    private final String key;
    private final Path path;
    private final OutputStream out;

    public LocalFileSink(Path root, String key) throws IOException {
        this.key = key;
        this.path = root.resolve(key);
        Files.createDirectories(root);
        this.out = Files.newOutputStream(path);
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        out.write(buffer, offset, length);
    }

    @Override
    public void commit() throws IOException {
        out.close();
    }

    @Override
    public void discard() {
        try {
            out.close();
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Failed to delete uploaded file {}", path, e);
        }
    }
}
