package tech.xingyunqi.template.infrastructure.files.upload;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.xingyunqi.template.common.oss.FileStorage;

/** 先写临时文件，分段结束时上传为对象（{@code Content-Type} 取用途表的 mime）。 */
public final class S3FileSink implements FileSink {

    private static final Logger LOG = LoggerFactory.getLogger(S3FileSink.class);

    private final FileStorage storage;
    private final String key;
    private final String contentType;
    private final Path temp;
    private final OutputStream out;
    private boolean uploaded;

    public S3FileSink(FileStorage storage, String key, String contentType) throws IOException {
        this.storage = storage;
        this.key = key;
        this.contentType = contentType;
        this.temp = Files.createTempFile("upload-", ".part");
        this.out = Files.newOutputStream(temp);
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
        try {
            storage.put(key, temp, contentType);
            uploaded = true;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void discard() {
        try {
            out.close();
            Files.deleteIfExists(temp);
            if (uploaded) {
                storage.delete(key);
            }
        } catch (IOException e) {
            LOG.warn("Failed to discard uploaded object {}", key, e);
        }
    }
}
