package tech.xingyunqi.template.parity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 同一请求发往本服务与 nestjs；目标由系统属性 {@code PARITY_TARGETS=java=<url>,nestjs=<url>} 指定。 */
final class Parity {

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
        return new Pair(
                send(targets.get("java"), method, path, headers, body),
                send(targets.get("nestjs"), method, path, headers, body));
    }

    /** 两侧分别使用各自的路径与请求头（如各自的令牌、各自的 id）。 */
    Pair requestEach(
            String method,
            String javaPath,
            Map<String, String> javaHeaders,
            String nestjsPath,
            Map<String, String> nestjsHeaders,
            byte[] body) {
        return new Pair(
                send(targets.get("java"), method, javaPath, javaHeaders, body),
                send(targets.get("nestjs"), method, nestjsPath, nestjsHeaders, body));
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
