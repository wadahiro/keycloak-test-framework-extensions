package io.github.wadahiro.keycloak.testframework.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

/**
 * A reverse proxy in the test JVM that forwards HTTP requests to the managed Keycloak server.
 *
 * <p>It stands in for the proxy of a real deployment: every forwarded request carries {@code X-Forwarded-For},
 * {@code X-Forwarded-Host}, {@code X-Forwarded-Proto} and {@code X-Forwarded-Port}, plus any extra request headers the
 * test configures. Browsers and HTTP clients use {@link #getBaseUrl()} instead of the server's own URL. Keycloak must
 * be configured to trust the forwarded headers ({@code proxy-headers=xforwarded}) so that it reports the forwarded
 * client address and builds its redirects, form actions and cookies for the proxy's address. Bodies are not rewritten.
 *
 * <p>With TLS the proxy listens on HTTPS with a self-signed certificate generated for this instance, terminates TLS and
 * forwards plain HTTP with {@code X-Forwarded-Proto: https}. It can request a client certificate and forward its
 * subject and PEM encoding in request headers, as a proxy that terminates mutual TLS does.
 */
public final class FrontProxy implements AutoCloseable {
    /** Whether the proxy requests a client certificate during the TLS handshake. */
    public enum ClientAuth {
        /** No client certificate is requested. */
        NONE,
        /** A client certificate is requested; the handshake also succeeds without one. */
        WANT,
        /** A client certificate is required; the handshake fails without one. */
        NEED
    }

    /** How the proxy listens and what it derives from a client certificate. */
    record Settings(boolean tls, ClientAuth clientAuth, String clientCertificateSubjectHeader, String clientCertificateHeader) {
        static final Settings HTTP = new Settings(false, ClientAuth.NONE, "", "");

        Settings {
            if (!tls && clientAuth != ClientAuth.NONE) throw new IllegalArgumentException("Client certificates require tls = true");
        }

        static Settings of(InjectFrontProxy annotation) {
            return new Settings(annotation.tls(), annotation.clientAuth(), annotation.clientCertificateSubjectHeader(),
                    annotation.clientCertificateHeader());
        }
    }

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
    private final Settings settings;
    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient client;
    private final String origin;
    private final KeyStore serverKeys;
    private final Map<String, Header> headers = new LinkedHashMap<>();
    private final Set<String> strippedHeaders = new LinkedHashSet<>();
    private String clientAddress;

    FrontProxy(String targetBaseUrl) {
        this(targetBaseUrl, Settings.HTTP);
    }

    FrontProxy(String targetBaseUrl, Settings settings) {
        this.settings = settings;
        URI target = URI.create(targetBaseUrl);
        targetOrigin = target.getScheme() + "://" + target.getRawAuthority();
        String path = target.getRawPath() == null ? "" : target.getRawPath();
        basePath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        serverKeys = settings.tls() ? Certificates.selfSigned("CN=localhost,O=Keycloak Test Front Proxy", true) : null;
        AtomicInteger threads = new AtomicInteger();
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "keycloak-front-proxy-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).executor(executor).build();
        try {
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", 0);
            if (settings.tls()) {
                HttpsServer https = HttpsServer.create(address, 0);
                SSLContext context = Certificates.serverContext(serverKeys);
                https.setHttpsConfigurator(new HttpsConfigurator(context) {
                    @Override
                    public void configure(HttpsParameters parameters) {
                        SSLParameters ssl = context.getDefaultSSLParameters();
                        ssl.setWantClientAuth(settings.clientAuth() == ClientAuth.WANT);
                        if (settings.clientAuth() == ClientAuth.NEED) ssl.setNeedClientAuth(true);
                        parameters.setSSLParameters(ssl);
                    }
                });
                server = https;
            } else {
                server = HttpServer.create(address, 0);
            }
        } catch (IOException failure) {
            client.close();
            executor.shutdownNow();
            throw new IllegalStateException("Unable to start the front proxy", failure);
        }
        server.createContext("/", this::forward);
        server.setExecutor(executor);
        server.start();
        origin = (settings.tls() ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The proxy URL that corresponds to the Keycloak server's base URL, for example {@code http://127.0.0.1:41234/auth}. */
    public String getBaseUrl() { return origin + basePath; }

    /** The proxy URL of a realm, for example {@code http://127.0.0.1:41234/realms/test}. */
    public String getRealmUrl(String realm) { return getBaseUrl() + "/realms/" + realm; }

    public int getPort() { return server.getAddress().getPort(); }

    public boolean isTls() { return settings.tls(); }

    /** The proxy's self-signed server certificate, issued for {@code 127.0.0.1} and {@code localhost}. */
    public X509Certificate getCertificate() {
        requireTls();
        return Certificates.certificate(serverKeys);
    }

    /** A PKCS #12 trust store holding {@link #getCertificate()}. */
    public KeyStore getTrustStore() {
        return Certificates.trustStore(getCertificate());
    }

    /** A client TLS context that trusts the proxy, for example for {@code HttpClient.newBuilder().sslContext(...)}. */
    public SSLContext sslContext() {
        return Certificates.clientContext(getTrustStore(), null);
    }

    /** A client TLS context that trusts the proxy and presents {@code certificate} when the proxy requests one. */
    public SSLContext sslContext(ClientCertificate certificate) {
        return Certificates.clientContext(getTrustStore(), certificate.getKeyStore());
    }

    /**
     * Adds {@code name: value} to the following forwarded requests, replacing a header of that name sent by the client
     * or set by the proxy, including a client certificate header. Calling it again with the same name replaces the
     * configured value.
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

    private void requireTls() {
        if (!settings.tls()) throw new IllegalStateException("The front proxy serves plain HTTP; inject it with tls = true");
    }

    private void forward(HttpExchange exchange) throws IOException {
        try {
            Map<String, Header> configured;
            Set<String> stripped;
            String forwardedFor;
            synchronized (this) {
                configured = Map.copyOf(headers);
                stripped = new LinkedHashSet<>(strippedHeaders);
                forwardedFor = clientAddress;
            }
            // The proxy owns its certificate headers: a client must not be able to supply them.
            for (String name : List.of(settings.clientCertificateSubjectHeader(), settings.clientCertificateHeader())) {
                if (!name.isEmpty()) stripped.add(name.toLowerCase(Locale.ROOT));
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
            request.setHeader("X-Forwarded-Proto", settings.tls() ? "https" : "http");
            request.setHeader("X-Forwarded-Port", Integer.toString(getPort()));
            X509Certificate presented = clientCertificate(exchange);
            if (presented != null) {
                if (!settings.clientCertificateSubjectHeader().isEmpty()) {
                    request.setHeader(settings.clientCertificateSubjectHeader(), presented.getSubjectX500Principal().getName());
                }
                if (!settings.clientCertificateHeader().isEmpty()) {
                    request.setHeader(settings.clientCertificateHeader(), escapedPem(presented));
                }
            }
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
        } catch (IOException | CertificateEncodingException | RuntimeException failure) {
            badGateway(exchange, failure);
        } finally {
            exchange.close();
        }
    }

    private static X509Certificate clientCertificate(HttpExchange exchange) {
        if (!(exchange instanceof HttpsExchange https)) return null;
        try {
            Certificate[] chain = https.getSSLSession().getPeerCertificates();
            return chain.length > 0 && chain[0] instanceof X509Certificate certificate ? certificate : null;
        } catch (SSLPeerUnverifiedException noCertificate) {
            return null;
        }
    }

    /** The URL-encoded PEM form that, for example, nginx forwards as {@code $ssl_client_escaped_cert}. */
    private static String escapedPem(X509Certificate certificate) throws CertificateEncodingException {
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        return URLEncoder.encode(pem, StandardCharsets.UTF_8).replace("+", "%20");
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
