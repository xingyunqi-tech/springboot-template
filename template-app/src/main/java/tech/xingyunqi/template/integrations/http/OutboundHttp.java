package tech.xingyunqi.template.integrations.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** 短信 / 微信 / 支付宝外呼：JDK HttpClient（不接入链路追踪），传输错误消息去掉 URL query。 */
public final class OutboundHttp {

    /** 响应状态码与原始 body。 */
    public record Response(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** 连接失败、超时或被中断；消息不含 URL query。 */
    public static final class TransportException extends Exception {

        TransportException(String message) {
            super(message, null, false, false);
        }
    }

    private static final Pattern URL_QUERY = Pattern.compile("(\\w+://[^\\s?#]*)\\?[^\\s#]*");

    private final HttpClient client;
    private final Duration timeout;

    public OutboundHttp(Duration timeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.timeout = timeout;
    }

    public Response send(HttpRequest.Builder request) throws TransportException {
        try {
            HttpResponse<String> response = client.send(
                    request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new TransportException(describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Request interrupted");
        }
    }

    public static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri);
    }

    static String describe(Throwable error) {
        String message = error.getMessage();
        String text = error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return stripQuery(text);
    }

    public static String stripQuery(@Nullable String text) {
        return text == null ? "" : URL_QUERY.matcher(text).replaceAll("$1");
    }
}
