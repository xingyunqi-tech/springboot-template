package tech.xingyunqi.template.infrastructure.files.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;

class SendFileTest {

    @TempDir
    Path root;

    Path file() throws IOException {
        Path path = root.resolve("a.png");
        Files.writeString(path, "PNGDATA1234");
        Files.setLastModifiedTime(path, FileTime.from(Instant.parse("2026-11-14T14:50:12.123Z")));
        return path;
    }

    MockHttpServletResponse send(Map<String, String> headers) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/a.png");
        headers.forEach(request::addHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();
        SendFile.send(request, response, file());
        return response;
    }

    @Test
    @DisplayName("输出 Express sendFile 的头：弱 ETag 为十六进制大小与毫秒 mtime，Last-Modified 两位日期")
    void headers() throws IOException {
        MockHttpServletResponse response = send(Map.of());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("ETag")).isEqualTo("W/\"b-" + Long.toHexString(1_794_667_812_123L) + "\"");
        assertThat(response.getHeader("Last-Modified")).isEqualTo("Sat, 14 Nov 2026 14:50:12 GMT");
        assertThat(response.getContentType()).isEqualTo("image/png");
        assertThat(response.getContentAsString()).isEqualTo("PNGDATA1234");
    }

    @Test
    @DisplayName("字节范围：单个或可合并的范围 206，多个范围完整输出，不可满足 416")
    void ranges() throws IOException {
        MockHttpServletResponse suffix = send(Map.of("Range", "bytes=-3"));
        assertThat(suffix.getStatus()).isEqualTo(206);
        assertThat(suffix.getHeader("Content-Range")).isEqualTo("bytes 8-10/11");
        assertThat(suffix.getContentAsString()).isEqualTo("234");

        MockHttpServletResponse combined = send(Map.of("Range", "bytes=0-1,2-4"));
        assertThat(combined.getHeader("Content-Range")).isEqualTo("bytes 0-4/11");

        assertThat(send(Map.of("Range", "bytes=0-1,3-4")).getStatus()).isEqualTo(200);

        assertThatThrownBy(() -> send(Map.of("Range", "bytes=100-200")))
                .isInstanceOfSatisfying(HttpStatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(416);
                    assertThat(e.headers()).containsEntry("Content-Range", "bytes */11");
                });
        assertThatThrownBy(() -> send(Map.of("Range", "bytes=-20")))
                .isInstanceOfSatisfying(
                        HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(416));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "bytes=abc",
                "bytes=",
                "bytes=1",
                "bytes=a-3",
                "bytes=1-b",
                "bytes=-",
                "bytes=+1-2",
                "bytes=0-1,x"
            })
    @DisplayName("范围语法错误（range-parser 1.3.0 返回 -2）时忽略 Range 完整输出")
    void malformedRangesAreIgnored(String range) throws IOException {
        MockHttpServletResponse response = send(Map.of("Range", range));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Content-Range")).isNull();
        assertThat(response.getContentAsString()).isEqualTo("PNGDATA1234");
    }

    @Test
    @DisplayName("范围两端允许空白，超出 long 的数字视为极大值")
    void rangeWhitespaceAndHugeNumbers() throws IOException {
        assertThat(send(Map.of("Range", "bytes= 1 - 2 ")).getHeader("Content-Range"))
                .isEqualTo("bytes 1-2/11");
        assertThat(send(Map.of("Range", "bytes=2-99999999999999999999")).getHeader("Content-Range"))
                .isEqualTo("bytes 2-10/11");
        assertThatThrownBy(() -> send(Map.of("Range", "bytes=99999999999999999999-")))
                .isInstanceOfSatisfying(
                        HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(416));
    }

    @Test
    @DisplayName("条件请求：If-Match / If-Unmodified-Since 不满足 412，If-None-Match / If-Modified-Since 命中 304")
    void conditionalRequests() throws IOException {
        String etag = send(Map.of()).getHeader("ETag");
        assertThat(send(Map.of("If-None-Match", etag)).getStatus()).isEqualTo(304);
        assertThat(send(Map.of("If-None-Match", etag, "Cache-Control", "no-cache"))
                        .getStatus())
                .isEqualTo(200);
        assertThat(send(Map.of("If-Modified-Since", "Sat, 14 Nov 2026 14:50:12 GMT"))
                        .getStatus())
                .isEqualTo(304);
        assertThat(send(Map.of("If-Match", etag)).getStatus()).isEqualTo(200);
        assertThatThrownBy(() -> send(Map.of("If-Match", "\"nope\"")))
                .isInstanceOfSatisfying(
                        HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(412));
        assertThatThrownBy(() -> send(Map.of("If-Unmodified-Since", "Thu, 01 Jan 1970 00:00:00 GMT")))
                .isInstanceOfSatisfying(
                        HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(412));
    }

    @Test
    @DisplayName("磁盘文件不存在时 404 Not Found")
    void missingFile() {
        assertThatThrownBy(() -> SendFile.send(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), root.resolve("missing.png")))
                .isInstanceOfSatisfying(HttpStatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.response()).isEqualTo("Not Found");
                });
    }
}
