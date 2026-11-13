package tech.xingyunqi.template.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.xingyunqi.template.common.core.exception.HttpStatusException;
import tech.xingyunqi.template.common.web.error.ErrorResponseWriter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** 先于路由与鉴权解析 application/json 请求体（Express 全局 body-parser，strict，上限 256kb）。 */
public class JsonBodyFilter extends OncePerRequestFilter {

    public static final int LIMIT = 256 * 1024;
    public static final String BODY_ATTRIBUTE = JsonBodyFilter.class.getName() + ".body";
    private static final String WHITESPACE = " \t\n\r";
    private static final Pattern CHARSET = Pattern.compile("(?i);\\s*charset\\s*=\\s*(\"[^\"]*\"|[^;\\s]*)");

    private final JsonMapper mapper;
    private final ErrorResponseWriter errors;

    public JsonBodyFilter(ErrorResponseWriter errors) {
        this.mapper = JsonMapper.builder(JsonFactory.builder()
                        .streamReadConstraints(StreamReadConstraints.builder()
                                .maxNumberLength(LIMIT)
                                .maxStringLength(LIMIT)
                                .maxNestingDepth(LIMIT)
                                .build())
                        .build())
                .build();
        this.errors = errors;
    }

    /** 已解析的请求体；非 JSON 请求为 null（即 JS 的 undefined）。 */
    public static @Nullable JsonNode parsedBody(HttpServletRequest request) {
        return request.getAttribute(BODY_ATTRIBUTE) instanceof JsonNode node ? node : null;
    }

    static boolean isJson(@Nullable String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return type.strip().toLowerCase(Locale.ROOT).equals("application/json");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isJson(request.getContentType())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] raw;
        JsonNode body;
        try {
            Charset charset = charset(request.getContentType());
            raw = read(request);
            body = parse(new String(raw, charset));
        } catch (HttpStatusException exception) {
            errors.write(request, response, exception);
            return;
        }
        request.setAttribute(BODY_ATTRIBUTE, body);
        chain.doFilter(new CachedBodyRequest(request, raw), response);
    }

    /** body-parser：charset 缺省为 utf-8，非 {@code utf-} 开头时 415。 */
    static Charset charset(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        String name = matcher.group(1).replace("\"", "").strip();
        if (!name.toLowerCase(Locale.ROOT).startsWith("utf-")) {
            throw new HttpStatusException(415, "unsupported charset \"" + name.toUpperCase(Locale.ROOT) + "\"");
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException exception) {
            return StandardCharsets.UTF_8;
        }
    }

    JsonNode parse(String decoded) {
        if (decoded.isEmpty()) {
            return JsonNodeFactory.instance.objectNode();
        }
        String text = decoded;
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        int start = 0;
        while (start < text.length() && WHITESPACE.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        if (start == text.length() || (text.charAt(start) != '{' && text.charAt(start) != '[')) {
            String token = start == text.length() ? "end of input" : String.valueOf(text.charAt(start));
            String head = text.length() > 32 ? text.substring(0, 32) : text;
            throw new HttpStatusException(400, "Unexpected token '" + token + "', '" + head + "' is not valid JSON");
        }
        try {
            return mapper.readTree(text);
        } catch (JacksonException exception) {
            throw new HttpStatusException(400, exception.getOriginalMessage());
        }
    }

    private static byte[] read(HttpServletRequest request) throws IOException {
        String encoding = request.getHeader("Content-Encoding");
        String normalized = encoding == null ? "identity" : encoding.strip().toLowerCase(Locale.ROOT);
        if (!normalized.equals("identity") && !normalized.equals("gzip") && !normalized.equals("deflate")) {
            throw new HttpStatusException(415, "unsupported content encoding \"" + normalized + "\"");
        }
        if (normalized.equals("identity") && request.getContentLengthLong() > LIMIT) {
            throw new HttpStatusException(413, "request entity too large");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream in = decoded(request.getInputStream(), normalized)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > LIMIT) {
                    throw new HttpStatusException(413, "request entity too large");
                }
                out.write(buffer, 0, read);
            }
        } catch (java.util.zip.ZipException exception) {
            throw new HttpStatusException(400, String.valueOf(exception.getMessage()));
        }
        return out.toByteArray();
    }

    private static InputStream decoded(InputStream in, String encoding) throws IOException {
        try {
            return switch (encoding) {
                case "gzip" -> new GZIPInputStream(in);
                case "deflate" -> new InflaterInputStream(in);
                default -> in;
            };
        } catch (java.util.zip.ZipException | java.io.EOFException exception) {
            throw new HttpStatusException(400, String.valueOf(exception.getMessage()));
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
