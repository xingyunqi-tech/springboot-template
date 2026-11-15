package tech.xingyunqi.template.infrastructure.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** 上传测试用的 multipart 请求体与本地存储目录工具。 */
public final class FileUploadBodies {

    public static final String UPLOAD = "/api/v1/files/upload";
    public static final String BOUNDARY = "xyz";
    public static final String MULTIPART = "multipart/form-data; boundary=" + BOUNDARY;
    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 'a', 'b', 'c'};

    private FileUploadBodies() {}

    /** 一个分段：{@code fileName} 为 null 时是文本字段。 */
    public record Part(String name, @Nullable String fileName, byte[] content) {}

    public static Part file(String fileName, byte[] content) {
        return new Part("file", fileName, content);
    }

    public static Part field(String name, String value) {
        return new Part(name, null, value.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] form(Part... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Part part : parts) {
            String disposition = "Content-Disposition: form-data; name=\"" + part.name() + "\""
                    + (part.fileName() == null ? "" : "; filename=\"" + part.fileName() + "\"");
            out.writeBytes(("--" + BOUNDARY + "\r\n" + disposition + "\r\n"
                            + (part.fileName() == null ? "" : "Content-Type: application/octet-stream\r\n") + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.writeBytes(part.content());
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    public static MockHttpServletRequestBuilder upload(@Nullable String purpose, String token, byte[] body) {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(
                        purpose == null ? UPLOAD : UPLOAD + "?purpose=" + purpose)
                .contentType(MULTIPART)
                .content(body);
        return token.isEmpty() ? builder : builder.header("Authorization", "Bearer " + token);
    }

    public static List<String> stored(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    public static void clear(Path root) throws IOException {
        for (String name : stored(root)) {
            Files.deleteIfExists(root.resolve(name));
        }
    }

    public static Path tempRoot(String prefix) {
        try {
            Path root = Files.createTempDirectory(prefix);
            root.toFile().deleteOnExit();
            return root;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
