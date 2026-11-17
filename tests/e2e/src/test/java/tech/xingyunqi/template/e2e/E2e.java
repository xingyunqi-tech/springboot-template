package tech.xingyunqi.template.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 黑盒访问 {@code APP_URL} 的服务与 {@code MAILPIT_URL} 的 Mailpit。每个实例使用独立的 {@code X-Forwarded-For}（服务以 {@code TRUST_PROXY=1}
 * 运行时限流计数互不影响）。
 */
final class E2e {

    static final String PASSWORD = md5("Secret00");
    static final String SEED_USER = "john.doe@example.com";
    static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CONFIRM_LINK = Pattern.compile("/confirm-email\\?hash=([^\\s&\"]+)");

    record Response(int status, HttpHeaders headers, byte[] body) {

        JsonNode json() {
            return JSON.readTree(body);
        }

        JsonNode data() {
            return json().path("data");
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    record HttpHeaders(Map<String, java.util.List<String>> values) {

        String first(String name) {
            return values.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }

    private final String appUrl = System.getProperty("APP_URL", "http://localhost:3000");
    private final String mailpitUrl = System.getProperty("MAILPIT_URL", "http://localhost:8025");
    private final String clientIp = "198.18." + RANDOM.nextInt(256) + "." + (1 + RANDOM.nextInt(254));

    static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String uniqueEmail(String prefix) {
        return "e2e." + prefix + "." + System.nanoTime() + "@example.com";
    }

    static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    Response get(String path, Map<String, String> headers) {
        return send("GET", path, headers, null, null);
    }

    Response get(String path) {
        return get(path, Map.of());
    }

    Response json(String method, String path, Map<String, String> headers, Object body) {
        return send(method, path, headers, "application/json", JSON.writeValueAsBytes(body));
    }

    Response post(String path, Map<String, String> headers) {
        return send("POST", path, headers, null, null);
    }

    Response upload(String purpose, String token, String filename, String contentType, byte[] content) {
        String boundary = "e2e-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(
                "POST",
                "/api/v1/files/upload?purpose=" + URLEncoder.encode(purpose, StandardCharsets.UTF_8),
                bearer(token),
                "multipart/form-data; boundary=" + boundary,
                body.toByteArray());
    }

    void register(String email) {
        Map<String, String> body =
                Map.of("email", email, "password", PASSWORD, "firstName", "E2E", "lastName", "Tester");
        Response response = json("POST", "/api/v1/auth/email/register", Map.of(), body);
        assertThat(response.status()).as(response.text()).isEqualTo(200);
    }

    JsonNode login(String email, String password, String userAgent) {
        Response response = json(
                "POST",
                "/api/v1/auth/email/login",
                Map.of("User-Agent", userAgent),
                Map.of("email", email, "password", password));
        assertThat(response.status()).as(response.text()).isEqualTo(200);
        return response.data();
    }

    /** 注册并通过 Mailpit 中的确认链接激活。 */
    void registerConfirmed(String email) {
        register(email);
        JsonNode message = waitForMail(email);
        Response confirmed = json("POST", "/api/v1/auth/email/confirm", Map.of(), Map.of("hash", confirmHash(message)));
        assertThat(confirmed.status()).as(confirmed.text()).isEqualTo(200);
    }

    JsonNode waitForMail(String email) {
        String query = URLEncoder.encode("to:\"" + email + "\"", StandardCharsets.UTF_8);
        JsonNode[] found = new JsonNode[1];
        poll("mail to " + email, () -> {
            JsonNode messages = mailpit("/api/v1/search?query=" + query).path("messages");
            if (messages.isEmpty()) {
                return false;
            }
            found[0] = mailpit("/api/v1/message/" + messages.get(0).path("ID").asString());
            return true;
        });
        return found[0];
    }

    static String confirmHash(JsonNode message) {
        Matcher matcher = CONFIRM_LINK.matcher(message.path("Text").asString());
        assertThat(matcher.find()).as(message.path("Text").asString()).isTrue();
        return java.net.URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    static void poll(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + ASYNC_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        fail("%s not observed within %s", what, ASYNC_TIMEOUT);
    }

    private JsonNode mailpit(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(mailpitUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<byte[]> response = execute(request);
        assertThat(response.statusCode()).as("Mailpit %s", path).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private Response send(String method, String path, Map<String, String> headers, String contentType, byte[] body) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("X-Forwarded-For", clientIp);
        all.putAll(headers);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(appUrl + path))
                .timeout(Duration.ofSeconds(30))
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        all.forEach(builder::header);
        HttpResponse<byte[]> response = execute(builder.build());
        return new Response(
                response.statusCode(), new HttpHeaders(response.headers().map()), response.body());
    }

    private static HttpResponse<byte[]> execute(HttpRequest request) {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
