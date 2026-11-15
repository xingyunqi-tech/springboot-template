package tech.xingyunqi.template.infrastructure.files.upload;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.fileupload2.core.MultipartInput;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.infrastructure.files.FileErrors;

/**
 * 流式接收 multipart 中字段名为 {@code file} 的单个文件，错误语义同 nestjs {@code FileInterceptor('file')}（multer 2.5.0 + busboy）。
 * 分隔符查找与分段读取用 commons-fileupload2 的 {@link MultipartInput}；出错时丢弃已写入的文件。
 */
public final class MultipartReceiver {

    public static final String FILE_FIELD = "file";
    static final int FIELD_SIZE_LIMIT = 1024 * 1024;
    static final int MAX_HEADER_SIZE = 16 * 1024;
    static final int FIELD_NESTING_DEPTH = 32;
    static final BigInteger FIELD_ARRAY_INDEX_LIMIT = BigInteger.valueOf(1000);

    private static final byte[] CRLF = {'\r', '\n'};
    private static final int BUFFER_SIZE = 8192;
    private static final Pattern BRACKET_PATH = Pattern.compile("[^\\[]+(?:\\[[^\\]]+\\])*(?:\\[\\])?");
    private static final Pattern ARRAY_INDEX = Pattern.compile("\\[([0-9]+)\\]");

    /** 读到文件部分的头时按客户端文件名检查，不通过时抛出。 */
    @FunctionalInterface
    public interface FileFilter {
        void check(String fileName);
    }

    /** 参数为客户端文件名，返回以随机键命名的存储目标。 */
    @FunctionalInterface
    public interface SinkFactory {
        FileSink open(String fileName) throws IOException;
    }

    private MultipartReceiver() {}

    /** 非 multipart 请求返回 null 且不读取请求体；multipart 中没有文件时返回 null。 */
    public static @Nullable ReceivedFile receive(
            @Nullable String contentType, InputStream body, FileFilter filter, SinkFactory sinks, long maxFileSize)
            throws IOException {
        if (!isMultipart(contentType)) {
            return null;
        }
        MediaType media = MediaType.parse(contentType == null ? "" : contentType);
        if (!"multipart/form-data".equals(media.type())) {
            throw HttpStatusException.of(415, "Unsupported content type");
        }
        String boundary = media.params().get("boundary");
        if (boundary == null || boundary.isEmpty()) {
            throw HttpStatusException.badRequest("Multipart: Boundary not found");
        }
        Receiver receiver = new Receiver(filter, sinks, maxFileSize);
        try {
            return receiver.run(body, boundary.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException | RuntimeException | Error e) {
            receiver.discardAll();
            throw e;
        }
    }

    public static boolean isMultipart(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String type = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip();
        return type.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    /** multer 对文本字段名的检查（嵌套深度、数组下标上限）。 */
    static void checkFieldName(String name) {
        if (name.chars().filter(c -> c == '[').count() > FIELD_NESTING_DEPTH) {
            throw HttpStatusException.badRequest("Field name nesting too deep - " + name);
        }
        if (BRACKET_PATH.matcher(name).matches()) {
            Matcher index = ARRAY_INDEX.matcher(name);
            while (index.find()) {
                if (new BigInteger(index.group(1)).compareTo(FIELD_ARRAY_INDEX_LIMIT) > 0) {
                    throw HttpStatusException.badRequest("Field name array index too large - " + name);
                }
            }
        }
    }

    /** busboy {@code basename}：取最后一个 {@code /} 或 {@code \} 之后的部分，{@code .} 与 {@code ..} 视为空。 */
    static String basename(String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String name = slash < 0 ? fileName : fileName.substring(slash + 1);
        return name.equals(".") || name.equals("..") ? "" : name;
    }

    static HttpStatusException unexpectedEnd() {
        return HttpStatusException.badRequest("Multipart: Unexpected end of form");
    }

    static HttpStatusException malformedHeader() {
        return HttpStatusException.badRequest("Multipart: Malformed part header");
    }

    private enum Kind {
        SKIP,
        FIELD,
        FILE
    }

    private static final class Receiver {

        private final FileFilter filter;
        private final SinkFactory sinks;
        private final long maxFileSize;
        private final List<FileSink> opened = new ArrayList<>();
        private @Nullable ReceivedFile file;

        Receiver(FileFilter filter, SinkFactory sinks, long maxFileSize) {
            this.filter = filter;
            this.sinks = sinks;
            this.maxFileSize = maxFileSize;
        }

        @Nullable
        ReceivedFile run(InputStream body, byte[] boundary) throws IOException {
            MultipartInput input = MultipartInput.builder()
                    .setInputStream(new SequenceInputStream(new ByteArrayInputStream(CRLF), body))
                    .setBoundary(boundary)
                    .setBufferSize(Math.max(BUFFER_SIZE, boundary.length * 2 + 8))
                    .get();
            boolean more;
            try {
                input.discardBodyData();
                more = input.readBoundary();
            } catch (MultipartInput.MalformedStreamException e) {
                throw unexpectedEnd();
            }
            while (more) {
                part(input);
                try {
                    more = input.readBoundary();
                } catch (MultipartInput.MalformedStreamException e) {
                    throw unexpectedEnd();
                }
            }
            return file;
        }

        private void part(MultipartInput input) throws IOException {
            Map<String, String> headers = PartHeaders.read(input, MAX_HEADER_SIZE);
            String name = "";
            Kind kind = Kind.SKIP;
            FileSink sink = null;
            String fileName = "";
            String disposition = headers.get("content-disposition");
            Disposition parsed = disposition == null ? null : Disposition.parse(disposition);
            if (parsed != null && parsed.type().equals("form-data")) {
                String rawName = parsed.params().get("name");
                if (rawName == null) {
                    throw HttpStatusException.badRequest("Field name missing");
                }
                name = rawName;
                String rawFileName = parsed.fileName();
                if (rawFileName == null) {
                    kind = Kind.FIELD;
                } else {
                    fileName = basename(rawFileName);
                    if (!fileName.isEmpty()) {
                        if (!FILE_FIELD.equals(name) || file != null) {
                            throw HttpStatusException.badRequest("Unexpected file field - " + name);
                        }
                        filter.check(fileName);
                        sink = sinks.open(fileName);
                        opened.add(sink);
                        kind = Kind.FILE;
                    }
                }
            }
            long size = readBody(input, kind, name, sink);
            if (kind == Kind.FIELD) {
                checkFieldName(name);
            } else if (sink != null) {
                sink.commit();
                file = new ReceivedFile(fileName, size, sink);
            }
        }

        private long readBody(MultipartInput input, Kind kind, String name, @Nullable FileSink sink)
                throws IOException {
            byte[] buffer = new byte[BUFFER_SIZE];
            long size = 0;
            InputStream part = input.newInputStream();
            try {
                int read;
                while ((read = part.read(buffer)) != -1) {
                    size += read;
                    if (kind == Kind.FIELD && size > FIELD_SIZE_LIMIT) {
                        throw HttpStatusException.badRequest("Field value too long - " + name);
                    }
                    if (sink != null) {
                        if (size > maxFileSize) {
                            throw FileErrors.tooLarge();
                        }
                        sink.write(buffer, 0, read);
                    }
                }
                part.close();
            } catch (MultipartInput.MalformedStreamException e) {
                throw unexpectedEnd();
            }
            return size;
        }

        void discardAll() {
            for (FileSink sink : opened) {
                sink.discard();
            }
        }
    }
}
