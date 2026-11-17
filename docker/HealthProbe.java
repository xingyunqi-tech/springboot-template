import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 请求 {@code args[0]}，2xx 时退出码 0，否则 1。 */
public final class HealthProbe {

    private HealthProbe() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: java -cp /app/probe HealthProbe <url>");
            System.exit(2);
        }
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(args[0]))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) {
                System.err.println(args[0] + " returned " + status);
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println(args[0] + " failed: " + e);
            System.exit(1);
        }
    }
}
