package tech.xingyunqi.template.infrastructure.files.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.infrastructure.files.FileErrors;

class MultipartReceiverTest {

    static final String CONTENT_TYPE = "multipart/form-data; boundary=xyz";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 'a', 'b', 'c'};

    static final class MemorySink implements FileSink {

        final String name;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean committed;
        boolean discarded;

        MemorySink(String name) {
            this.name = name;
        }

        @Override
        public String key() {
            return "key-" + name;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            bytes.write(buffer, offset, length);
        }

        @Override
        public void commit() {
            committed = true;
        }

        @Override
        public void discard() {
            discarded = true;
        }
    }

    final List<MemorySink> sinks = new ArrayList<>();

    @Nullable
    ReceivedFile receive(String contentType, byte[] body) throws IOException {
        return receive(contentType, new ByteArrayInputStream(body), 1024);
    }

    @Nullable
    ReceivedFile receive(String contentType, InputStream body, long maxFileSize) throws IOException {
        return MultipartReceiver.receive(
                contentType,
                body,
                fileName -> {
                    if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                        throw FileErrors.typeNotAllowed();
                    }
                },
                fileName -> {
                    MemorySink sink = new MemorySink(fileName);
                    sinks.add(sink);
                    return sink;
                },
                maxFileSize);
    }

    static byte[] form(String preamble, byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(preamble.getBytes(StandardCharsets.ISO_8859_1));
        for (byte[] part : parts) {
            out.writeBytes("--xyz\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(part);
            out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.writeBytes("--xyz--\r\n".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    static byte[] textPart(String name, String value) {
        return ("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value)
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] filePart(String disposition, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("Content-Disposition: form-data; " + disposition + "\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(content);
        return out.toByteArray();
    }

    static byte[] filePart(byte[] content) {
        return filePart("name=\"file\"; filename=\"a.png\"", content);
    }

    static HttpStatusException error(ThrowingCall call) {
        try {
            call.run();
        } catch (HttpStatusException e) {
            return e;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("no error");
    }

    @FunctionalInterface
    interface ThrowingCall {
        void run() throws IOException;
    }

    static String message(HttpStatusException error) {
        return error.response() instanceof Map<?, ?> body
                ? String.valueOf(body.get("message"))
                : String.valueOf(error.response());
    }

    /** 每次 read 只返回一个字节。 */
    static InputStream oneByteChunks(byte[] body) {
        return new ByteArrayInputStream(body) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
    }

    @Test
    @DisplayName("逐字节到达、带 preamble 与形似分隔符的内容时完整接收文件")
    void oneByteChunksWithPreamble() throws IOException {
        byte[] content = concat(PNG, "\r\n--xy\r\n-".getBytes(StandardCharsets.ISO_8859_1), PNG);
        byte[] body = form("ignored preamble -- xyz\r\n--xy\r\n", textPart("note", "--xyz"), filePart(content));

        ReceivedFile file = receive(CONTENT_TYPE, oneByteChunks(body), 1024);

        assertThat(file).isNotNull();
        assertThat(file.originalName()).isEqualTo("a.png");
        assertThat(file.size()).isEqualTo(content.length);
        assertThat(sinks.getFirst().bytes.toByteArray()).isEqualTo(content);
        assertThat(sinks.getFirst().committed).isTrue();
    }

    @Test
    @DisplayName("非 multipart 请求不读取请求体并返回 null")
    void nonMultipartIsNoFile() throws IOException {
        InputStream untouched = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("body must not be read");
            }
        };
        assertThat(receive("application/json", untouched, 10)).isNull();
        assertThat(MultipartReceiver.receive(null, untouched, name -> {}, name -> new MemorySink(name), 10))
                .isNull();
    }

    @Test
    @DisplayName("没有文件部分、空文件名、文本字段 file 都视为没有文件")
    void noFile() throws IOException {
        assertThat(receive(CONTENT_TYPE, form("", textPart("x", "1")))).isNull();
        assertThat(receive(CONTENT_TYPE, form("", textPart("file", "text")))).isNull();
        assertThat(receive(CONTENT_TYPE, form("", filePart("name=\"file\"; filename=\"\"", PNG))))
                .isNull();
        assertThat(receive(CONTENT_TYPE, form("", filePart("name=\"file\"; filename=\"dir/..\"", PNG))))
                .isNull();
        assertThat(receive(CONTENT_TYPE, "--xyz--\r\n".getBytes(StandardCharsets.ISO_8859_1)))
                .isNull();
        assertThat(sinks).isEmpty();
    }

    @Test
    @DisplayName("文件名去掉目录部分，filename* 优先")
    void fileNames() throws IOException {
        ReceivedFile file = receive(CONTENT_TYPE, form("", filePart("name=\"file\"; filename=\"../../x.png\"", PNG)));
        assertThat(file).isNotNull();
        assertThat(file.originalName()).isEqualTo("x.png");

        ReceivedFile extended = receive(
                CONTENT_TYPE,
                form("", filePart("name=\"file\"; filename=\"a.txt\"; filename*=UTF-8''%E5%9B%BE.png", PNG)));
        assertThat(extended).isNotNull();
        assertThat(extended.originalName()).isEqualTo("图.png");
    }

    @Test
    @DisplayName("Content-Type 与 boundary 错误")
    void contentTypeErrors() {
        HttpStatusException mixed = error(() -> receive("multipart/mixed; boundary=xyz", new byte[] {'x'}));
        assertThat(mixed.status()).isEqualTo(415);
        assertThat(message(mixed)).isEqualTo("Unsupported content type");

        HttpStatusException noBoundary = error(() -> receive("multipart/form-data", new byte[] {'x'}));
        assertThat(noBoundary.status()).isEqualTo(400);
        assertThat(message(noBoundary)).isEqualTo("Multipart: Boundary not found");
    }

    @Test
    @DisplayName("找不到分隔符、截断与畸形分段头")
    void malformedBodies() {
        assertThat(message(error(() -> receive(CONTENT_TYPE, "garbage".getBytes(StandardCharsets.ISO_8859_1)))))
                .isEqualTo("Multipart: Unexpected end of form");
        assertThat(message(error(() -> receive(CONTENT_TYPE, new byte[0]))))
                .isEqualTo("Multipart: Unexpected end of form");
        byte[] complete = form("", filePart(PNG));
        byte[] truncated = java.util.Arrays.copyOf(complete, complete.length - 11);
        assertThat(message(error(() -> receive(CONTENT_TYPE, truncated))))
                .isEqualTo("Multipart: Unexpected end of form");
        assertThat(sinks.getFirst().discarded).isTrue();
        assertThat(message(error(() -> receive(
                        CONTENT_TYPE,
                        "--xyz\r\nBad Header\r\n\r\nabc\r\n--xyz--\r\n".getBytes(StandardCharsets.ISO_8859_1)))))
                .isEqualTo("Multipart: Malformed part header");
        for (String body :
                List.of("--xyz\r\n\r\nabc\r\n--xyz--\r\n", "--xyz\r\nBad Header", "--xyz\r\nA: b\rc\r\n\r\n")) {
            assertThat(message(error(() -> receive(CONTENT_TYPE, body.getBytes(StandardCharsets.ISO_8859_1)))))
                    .as(body)
                    .isEqualTo("Multipart: Malformed part header");
        }
        assertThat(message(error(() -> receive(
                        CONTENT_TYPE,
                        "--xyz\r\nContent-Disposition: form-data".getBytes(StandardCharsets.ISO_8859_1)))))
                .isEqualTo("Multipart: Unexpected end of form");
    }

    @Test
    @DisplayName("字段名缺失、意外的文件字段与第二个 file")
    void unexpectedFields() {
        assertThat(message(error(() -> receive(CONTENT_TYPE, form("", filePart("filename=\"a.png\"", PNG))))))
                .isEqualTo("Field name missing");
        assertThat(message(error(
                        () -> receive(CONTENT_TYPE, form("", filePart("name=\"other\"; filename=\"a.png\"", PNG))))))
                .isEqualTo("Unexpected file field - other");
        assertThat(message(error(() -> receive(CONTENT_TYPE, form("", filePart(PNG), filePart(PNG))))))
                .isEqualTo("Unexpected file field - file");
        assertThat(sinks).hasSize(1);
        assertThat(sinks.getFirst().discarded).isTrue();
    }

    @Test
    @DisplayName("扩展名在读到文件头时检查")
    void fileFilterRunsOnHeaders() {
        HttpStatusException rejected =
                error(() -> receive(CONTENT_TYPE, form("", filePart("name=\"file\"; filename=\"a.txt\"", PNG))));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(sinks).isEmpty();
    }

    @Test
    @DisplayName("文件超过单文件上限时在上限处停止读取请求体并 413，已写入的文件被丢弃")
    void fileTooLargeStopsReading() {
        byte[] body = form("", filePart(new byte[1024 * 1024]));
        CountingInputStream counting = new CountingInputStream(body);

        HttpStatusException tooLarge = error(() -> receive(CONTENT_TYPE, counting, 1024));

        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(counting.read).isLessThan(64 * 1024);
        assertThat(sinks.getFirst().bytes.size()).isLessThanOrEqualTo(1024);
        assertThat(sinks.getFirst().discarded).isTrue();
    }

    static final class CountingInputStream extends ByteArrayInputStream {

        long read;

        CountingInputStream(byte[] body) {
            super(body);
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) {
                read++;
            }
            return value;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            int count = super.read(b, off, len);
            if (count > 0) {
                read += count;
            }
            return count;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a[1001]", "a[b][2000][]"})
    @DisplayName("文本字段名数组下标超过 1000 时 400")
    void arrayIndexTooLarge(String field) {
        assertThat(message(error(() -> receive(CONTENT_TYPE, form("", filePart(PNG), textPart(field, "1"))))))
                .isEqualTo("Field name array index too large - " + field);
        assertThat(sinks.getFirst().discarded).isTrue();
    }

    @Test
    @DisplayName("文本字段名嵌套超过 32 层时 400")
    void nestingTooDeep() {
        String field = "a" + "[b]".repeat(33);
        assertThat(message(error(() -> receive(CONTENT_TYPE, form("", textPart(field, "1"))))))
                .isEqualTo("Field name nesting too deep - " + field);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"a[1000]", "a[b][b][b][b][b][b][b][b][b][b][b][b][b][b][b][b]", "a[1001]x", "[1001]", "a[1001"})
    @DisplayName("合法的文本字段名不影响接收")
    void acceptedFieldNames(String field) throws IOException {
        assertThat(receive(CONTENT_TYPE, form("", textPart(field, "1"), filePart(PNG))))
                .isNotNull();
    }

    @Test
    @DisplayName("文本字段超过 1MB 时 400")
    void fieldValueTooLong() {
        String value = "x".repeat(MultipartReceiver.FIELD_SIZE_LIMIT + 1);
        assertThat(message(error(() -> receive(CONTENT_TYPE, form("", textPart("note", value))))))
                .isEqualTo("Field value too long - note");
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
