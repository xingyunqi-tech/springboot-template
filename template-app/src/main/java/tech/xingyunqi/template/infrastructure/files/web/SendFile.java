package tech.xingyunqi.template.infrastructure.files.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.infrastructure.files.FilePurpose;

/** Express {@code res.sendFile}（send 1.x）与 {@code res.redirect} 的输出。 */
final class SendFile {

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern(
                    "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);
    private static final Pattern NO_CACHE = Pattern.compile("(?:^|,)\\s*?no-cache\\s*?(?:,|$)");
    private static final Pattern BYTES_RANGE = Pattern.compile("^ *bytes=");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    /** mime-types {@code contentType()}：上传用途表中出现的扩展名。 */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("zip", "application/zip"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("pdf", "application/pdf"));

    private SendFile() {}

    /**
     * 弱 ETag、{@code Last-Modified}、{@code Cache-Control}、{@code Accept-Ranges}；{@code If-Match} /
     * {@code If-Unmodified-Since} 不满足时 412，条件请求命中时 304，单个字节范围 206，不可满足的范围 416；磁盘文件不存在时 404 {@code Not Found}。
     */
    static void send(HttpServletRequest request, HttpServletResponse response, Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            throw new HttpStatusException(404, "Not Found");
        }
        if (!attributes.isRegularFile()) {
            throw new HttpStatusException(404, "Not Found");
        }
        long size = attributes.size();
        long mtime = attributes.lastModifiedTime().toMillis();
        String etag = "W/\"" + Long.toHexString(size) + "-" + Long.toHexString(mtime) + "\"";
        String lastModified = HTTP_DATE.format(Instant.ofEpochMilli(mtime));
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "public, max-age=0");
        response.setHeader("Last-Modified", lastModified);
        response.setHeader("ETag", etag);
        if (isConditional(request)) {
            if (isPreconditionFailure(request, etag, lastModified)) {
                throw new HttpStatusException(412, "Precondition Failed");
            }
            if (isFresh(request, etag, lastModified)) {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }
        }
        response.setContentType(contentType(path));
        long offset = 0;
        long length = size;
        String range = request.getHeader("Range");
        if (range != null && BYTES_RANGE.matcher(range).find() && isRangeFresh(request, etag, lastModified)) {
            long[] parsed = singleRange(range, size);
            if (parsed != null && parsed.length == 0) {
                throw new HttpStatusException(416, "Range Not Satisfiable", Map.of("Content-Range", "bytes */" + size));
            }
            if (parsed != null) {
                response.setStatus(206);
                response.setHeader("Content-Range", "bytes " + parsed[0] + "-" + parsed[1] + "/" + size);
                offset = parsed[0];
                length = parsed[1] - parsed[0] + 1;
            }
        }
        response.setContentLengthLong(length);
        if ("HEAD".equals(request.getMethod())) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            in.skipNBytes(offset);
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[8192];
            long remaining = length;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    /** Express {@code res.redirect}（302）：正文按 {@code Accept} 选择 text 或 html。 */
    static void redirect(HttpServletRequest request, HttpServletResponse response, String url) throws IOException {
        String accept = request.getHeader("Accept");
        String body;
        if (accept != null && accept.contains("text/html") && !accept.contains("text/plain")) {
            String escaped = url.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
            body = "<!DOCTYPE html><head><title>Found</title></head><body><p>Found. Redirecting to " + escaped
                    + "</p></body>";
            response.setContentType("text/html; charset=utf-8");
        } else {
            body = "Found. Redirecting to " + url;
            response.setContentType("text/plain; charset=utf-8");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", url);
        response.setHeader("Vary", "Accept");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    static String contentType(Path path) {
        String type = CONTENT_TYPES.get(FilePurpose.extension(path.getFileName().toString()));
        return type == null ? "application/octet-stream" : type;
    }

    private static boolean isConditional(HttpServletRequest request) {
        return request.getHeader("If-Match") != null
                || request.getHeader("If-Unmodified-Since") != null
                || request.getHeader("If-None-Match") != null
                || request.getHeader("If-Modified-Since") != null;
    }

    /** send {@code isPreconditionFailure}。 */
    static boolean isPreconditionFailure(HttpServletRequest request, String etag, String lastModified) {
        String match = request.getHeader("If-Match");
        if (match != null && !match.isEmpty()) {
            if ("*".equals(match)) {
                return false;
            }
            for (String token : match.split(",", -1)) {
                String candidate = token.strip();
                if (candidate.equals(etag) || ("W/" + candidate).equals(etag) || candidate.equals("W/" + etag)) {
                    return false;
                }
            }
            return true;
        }
        String unmodifiedSince = request.getHeader("If-Unmodified-Since");
        Long since = unmodifiedSince == null ? null : parseHttpDate(unmodifiedSince);
        if (since != null) {
            Long modified = parseHttpDate(lastModified);
            return modified == null || modified > since;
        }
        return false;
    }

    /** npm {@code fresh}。 */
    static boolean isFresh(HttpServletRequest request, String etag, String lastModified) {
        String modifiedSince = request.getHeader("If-Modified-Since");
        String noneMatch = request.getHeader("If-None-Match");
        if (isEmpty(modifiedSince) && isEmpty(noneMatch)) {
            return false;
        }
        String cacheControl = request.getHeader("Cache-Control");
        if (cacheControl != null && NO_CACHE.matcher(cacheControl).find()) {
            return false;
        }
        if (!isEmpty(noneMatch) && !"*".equals(noneMatch)) {
            boolean matched = false;
            for (String token : noneMatch.split(",", -1)) {
                String match = token.strip();
                if (match.equals(etag) || ("W/" + match).equals(etag) || match.equals("W/" + etag)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        if (!isEmpty(modifiedSince)) {
            Long modified = parseHttpDate(lastModified);
            Long since = parseHttpDate(modifiedSince);
            return modified != null && since != null && modified <= since;
        }
        return true;
    }

    /** send {@code isRangeFresh}：没有 {@code If-Range} 或其与 ETag / Last-Modified 一致。 */
    private static boolean isRangeFresh(HttpServletRequest request, String etag, String lastModified) {
        String ifRange = request.getHeader("If-Range");
        if (isEmpty(ifRange)) {
            return true;
        }
        if (ifRange.indexOf('"') >= 0) {
            return ifRange.contains(etag);
        }
        Long since = parseHttpDate(ifRange);
        Long modified = parseHttpDate(lastModified);
        return since != null && modified != null && modified <= since;
    }

    /** range-parser 1.3.0（{@code combine: true}）：合并后恰好一个范围时返回它；多个范围或语法错误时为 null（按完整响应处理）；没有可满足的范围为空数组。 */
    static long @Nullable [] singleRange(String header, long size) {
        String spec = header.substring(header.indexOf('=') + 1);
        List<long[]> ranges = new ArrayList<>();
        for (String part : spec.split(",", -1)) {
            int dash = part.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String startText = part.substring(0, dash).strip();
            String endText = part.substring(dash + 1).strip();
            Long start = parsePos(startText);
            Long end = parsePos(endText);
            if (startText.isEmpty()) {
                start = end == null ? null : size - end;
                end = size - 1;
            } else if (endText.isEmpty()) {
                end = size - 1;
            }
            if (end != null && end > size - 1) {
                end = size - 1;
            }
            if (start == null || end == null) {
                return null;
            }
            if (start > end || start < 0) {
                continue;
            }
            ranges.add(new long[] {start, end});
        }
        if (ranges.isEmpty()) {
            return new long[0];
        }
        ranges.sort(Comparator.comparingLong(range -> range[0]));
        List<long[]> combined = new ArrayList<>();
        for (long[] range : ranges) {
            long[] current = combined.isEmpty() ? null : combined.getLast();
            if (current == null || range[0] > current[1] + 1) {
                combined.add(range.clone());
            } else if (range[1] > current[1]) {
                current[1] = range[1];
            }
        }
        return combined.size() == 1 ? combined.getFirst() : null;
    }

    /** range-parser {@code parsePos}：只含十进制数字时取其值（超出 long 时取 {@link Long#MAX_VALUE}），否则为 null。 */
    private static @Nullable Long parsePos(String value) {
        if (!DIGITS.matcher(value).matches()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static @Nullable Long parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }
}
