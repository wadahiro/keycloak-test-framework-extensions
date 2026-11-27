package io.github.wadahiro.keycloak.testframework.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A reverse proxy in the test JVM that forwards HTTP requests to the managed Keycloak server.
 *
 * <p>It stands in for the proxy of a real deployment: every forwarded request carries {@code X-Forwarded-For},
 * {@code X-Forwarded-Host}, {@code X-Forwarded-Proto} and {@code X-Forwarded-Port}, plus any extra request headers the
 * test configures. Browsers and HTTP clients use {@link #getBaseUrl()} instead of the server's own URL. Keycloak must
 * be configured to trust the forwarded headers ({@code proxy-headers=xforwarded}) so that it reports the forwarded
 * client address and builds its redirects, form actions and cookies for the proxy's address. Bodies are not rewritten.
 */
public final class FrontProxy implements AutoCloseable {
    /** Request headers the proxy never forwards from the client: hop-by-hop headers, and those the proxy sets itself. */
    private static final Set<String> DROPPED_REQUEST_HEADERS = Set.of("connection", "keep-alive", "proxy-connection",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect",
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port");
    /** Response headers recomputed by the proxy's own HTTP server. */
    private static final Set<String> DROPPED_RESPONSE_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length", "date");
    /** Headers the JDK HTTP client refuses to send. */
    private static final Set<String> RESTRICTED_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final String targetOrigin;
    private final String basePath;
    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient client;
    private final String origin;
    private final Map<String, Header> headers = new LinkedHashMap<>();
    private final Set<String> strippedHeaders = new LinkedHashSet<>();
    private String clientAddress;

    FrontProxy(String targetBaseUrl) {
        URI target = URI.create(targetBaseUrl);
        targetOrigin = target.getScheme() + "://" + target.getRawAuthority();
        String path = target.getRawPath() == null ? "" : target.getRawPath();
        basePath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        AtomicInteger threads = new AtomicInteger();
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "keycloak-front-proxy-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).executor(executor).build();
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failure) {
            client.close();
            executor.shutdownNow();
            throw new IllegalStateException("Unable to start the front proxy", failure);
        }
        server.createContext("/", this::forward);
        server.setExecutor(executor);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The proxy URL that corresponds to the Keycloak server's base URL, for example {@code http://127.0.0.1:41234/auth}. */
    public String getBaseUrl() { return origin + basePath; }

    /** The proxy URL of a realm, for example {@code http://127.0.0.1:41234/realms/test}. */
    public String getRealmUrl(String realm) { return getBaseUrl() + "/realms/" + realm; }

    public int getPort() { return server.getAddress().getPort(); }

    /**
     * Adds {@code name: value} to the following forwarded requests, replacing a header of that name sent by the client
     * or set by the proxy. Calling it again with the same name replaces the configured value.
     */
    public synchronized FrontProxy header(String name, String value) {
        if (name == null || name.isBlank() || value == null) throw new IllegalArgumentException("Header name and value are required");
        if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The front proxy cannot set the " + name + " header");
        }
        headers.put(name.toLowerCase(Locale.ROOT), new Header(name, value));
        return this;
    }

    /** Stops adding a header configured with {@link #header(String, String)}. */
    public synchronized FrontProxy removeHeader(String name) {
        headers.remove(name.toLowerCase(Locale.ROOT));
        return this;
    }

    /** Drops a header the client sends, as a proxy does that does not let clients supply it. */
    public synchronized FrontProxy stripHeader(String name) {
        strippedHeaders.add(name.toLowerCase(Locale.ROOT));
        return this;
    }

    /**
     * Reports {@code address} as the client address in {@code X-Forwarded-For}; {@code null} reports the address of the
     * connecting client, which is the default.
     */
    public synchronized FrontProxy clientAddress(String address) {
        clientAddress = address;
        return this;
    }

    /** Clears configured headers, stripped headers and the client address. The supplier calls it before each test. */
    public synchronized void reset() {
        headers.clear();
        strippedHeaders.clear();
        clientAddress = null;
    }

    @Override
    public void close() {
        server.stop(0);
        client.close();
        executor.shutdownNow();
    }

    private void forward(HttpExchange exchange) throws IOException {
        try {
            Map<String, Header> configured;
            Set<String> stripped;
            String forwardedFor;
            synchronized (this) {
                configured = Map.copyOf(headers);
                stripped = Set.copyOf(strippedHeaders);
                forwardedFor = clientAddress;
            }
            URI requested = exchange.getRequestURI();
            String query = requested.getRawQuery();
            URI upstream = URI.create(targetOrigin + requested.getRawPath() + (query == null ? "" : "?" + query));
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            HttpRequest.Builder request = HttpRequest.newBuilder(upstream).timeout(Duration.ofSeconds(60))
                    .method(exchange.getRequestMethod(), requestBody.length == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(requestBody));
            for (var header : exchange.getRequestHeaders().entrySet()) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (DROPPED_REQUEST_HEADERS.contains(name) || stripped.contains(name) || configured.containsKey(name)) continue;
                for (String value : header.getValue()) request.header(header.getKey(), value);
            }
            String host = exchange.getRequestHeaders().getFirst("Host");
            request.setHeader("X-Forwarded-For", forwardedFor != null ? forwardedFor
                    : exchange.getRemoteAddress().getAddress().getHostAddress());
            request.setHeader("X-Forwarded-Host", host != null ? host : "127.0.0.1:" + getPort());
            request.setHeader("X-Forwarded-Proto", "http");
            request.setHeader("X-Forwarded-Port", Integer.toString(getPort()));
            for (Header header : configured.values()) request.setHeader(header.name(), header.value());

            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            for (var header : response.headers().map().entrySet()) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (name.startsWith(":") || DROPPED_RESPONSE_HEADERS.contains(name)) continue;
                for (String value : header.getValue()) exchange.getResponseHeaders().add(header.getKey(), value);
            }
            byte[] body = response.body();
            int status = response.statusCode();
            boolean empty = body.length == 0 || status == 204 || status == 304 || "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
            exchange.sendResponseHeaders(status, empty ? -1 : body.length);
            if (!empty) exchange.getResponseBody().write(body);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            badGateway(exchange, failure);
        } catch (IOException | RuntimeException failure) {
            badGateway(exchange, failure);
        } finally {
            exchange.close();
        }
    }

    private static void badGateway(HttpExchange exchange, Exception failure) {
        try {
            byte[] body = ("Front proxy cannot reach Keycloak: " + failure).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().put("Content-Type", List.of("text/plain; charset=UTF-8"));
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException | RuntimeException ignored) {
            // The response was already started or the client disconnected.
        }
    }

    private record Header(String name, String value) { }
}
