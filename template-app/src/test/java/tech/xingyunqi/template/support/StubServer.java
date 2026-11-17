package tech.xingyunqi.template.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** 本地 HTTP 桩：按路径返回预设响应，并记录收到的请求。 */
public final class StubServer implements AutoCloseable {

    /** 收到的请求。 */
    public record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }

    /** 预设响应。 */
    public record Reply(int status, String body) {

        public static Reply json(String body) {
            return new Reply(200, body);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Reply>> routes = new ConcurrentHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubServer on(String path, Function<Request, Reply> handler) {
        routes.put(path, handler);
        return this;
    }

    public StubServer on(String path, Reply reply) {
        return on(path, request -> reply);
    }

    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public List<Request> requests(String path) {
        return requests().stream().filter(r -> r.path().equals(path)).toList();
    }

    public void reset() {
        routes.clear();
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        Request request = new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                query == null ? "" : query,
                Map.copyOf(exchange.getRequestHeaders()),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requests.add(request);
        Function<Request, Reply> handler = routes.get(request.path());
        Reply reply = handler == null ? new Reply(404, "{}") : handler.apply(request);
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
