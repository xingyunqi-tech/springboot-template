package tech.xingyunqi.template.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 同一请求发往本服务与 nestjs；目标由系统属性 {@code PARITY_TARGETS=java=<url>,nestjs=<url>} 指定。两侧均以 {@code THROTTLE_ENABLED=true}、
 * {@code TRUST_PROXY=1} 运行：未显式指定时每个请求带不同的 {@code X-Forwarded-For}；每对响应的限流头集合一致、取值为整数、 {@code X-RateLimit-Limit}
 * 相等，且不得出现 429（{@link #requestThrottled} 除外）。
 */
final class Parity {

    static final List<String> RATE_LIMIT_HEADERS =
            List.of("x-ratelimit-limit", "x-ratelimit-remaining", "x-ratelimit-reset", "retry-after");
    private static final AtomicInteger CLIENT_IPS = new AtomicInteger(new SecureRandom().nextInt(1 << 24));

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    record Result(int status, Map<String, java.util.List<String>> headers, byte[] body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> String.join(", ", e.getValue()))
                    .findFirst()
                    .orElse(null);
        }

        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    record Pair(Result java, Result nestjs) {}

    private final Map<String, String> targets;

    private Parity(Map<String, String> targets) {
        this.targets = targets;
    }

    static Parity fromSystemProperties() {
        String spec = System.getProperty("PARITY_TARGETS", "java=http://localhost:3000,nestjs=http://localhost:3021");
        Map<String, String> targets = new LinkedHashMap<>();
        for (String part : spec.split(",")) {
            String[] kv = part.split("=", 2);
            targets.put(kv[0].strip(), kv[1].strip());
        }
        return new Parity(targets);
    }

    Pair request(String method, String path, Map<String, String> headers, byte[] body) {
        return checked(
                send(targets.get("java"), method, path, headers, body),
                send(targets.get("nestjs"), method, path, headers, body),
                false);
    }

    /** 预期两侧都已限流的请求，允许 429。 */
    Pair requestThrottled(String method, String path, Map<String, String> headers, byte[] body) {
        return checked(
                send(targets.get("java"), method, path, headers, body),
                send(targets.get("nestjs"), method, path, headers, body),
                true);
    }

    /** 两侧分别使用各自的路径与请求头（如各自的令牌、各自的 id）。 */
    Pair requestEach(
            String method,
            String javaPath,
            Map<String, String> javaHeaders,
            String nestjsPath,
            Map<String, String> nestjsHeaders,
            byte[] body) {
        return checked(
                send(targets.get("java"), method, javaPath, javaHeaders, body),
                send(targets.get("nestjs"), method, nestjsPath, nestjsHeaders, body),
                false);
    }

    private static Pair checked(Result java, Result nestjs, boolean expectThrottled) {
        if (!expectThrottled) {
            for (Result result : List.of(java, nestjs)) {
                assertThat(result.status())
                        .as("unexpected 429: %s", new String(result.body(), StandardCharsets.UTF_8))
                        .isNotEqualTo(429);
            }
        }
        assertSameRateLimitHeaders(java, nestjs);
        return new Pair(java, nestjs);
    }

    /** 限流头集合一致且均为整数；{@code X-RateLimit-Limit} 取值一致。 */
    static void assertSameRateLimitHeaders(Result java, Result nestjs) {
        Set<String> present = rateLimitHeaders(java);
        assertThat(present).as("rate limit headers").isEqualTo(rateLimitHeaders(nestjs));
        for (Result result : List.of(java, nestjs)) {
            for (String name : present) {
                assertThat(result.header(name)).as(name).matches("\\d+");
            }
        }
        if (present.contains("x-ratelimit-limit")) {
            assertThat(java.header("x-ratelimit-limit")).isEqualTo(nestjs.header("x-ratelimit-limit"));
        }
    }

    private static Set<String> rateLimitHeaders(Result result) {
        Set<String> present = new TreeSet<>();
        for (String name : RATE_LIMIT_HEADERS) {
            if (result.header(name) != null) {
                present.add(name);
            }
        }
        return present;
    }

    /** 10.0.0.0/8 内依次取地址。 */
    static String nextClientIp() {
        int n = CLIENT_IPS.incrementAndGet() & 0xFFFFFF;
        return "10." + (n >> 16) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }

    Pair request(String method, String path) {
        return request(method, path, Map.of(), new byte[0]);
    }

    Pair request(String method, String path, Map<String, String> headers) {
        return request(method, path, headers, new byte[0]);
    }

    private static Result send(String base, String method, String path, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .method(
                        method,
                        body.length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        if (headers.keySet().stream().noneMatch("X-Forwarded-For"::equalsIgnoreCase)) {
            builder.header("X-Forwarded-For", nextClientIp());
        }
        try {
            HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Result(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
