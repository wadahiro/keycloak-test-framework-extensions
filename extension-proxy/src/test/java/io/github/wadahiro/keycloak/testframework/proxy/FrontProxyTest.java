package io.github.wadahiro.keycloak.testframework.proxy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontProxyTest {
    private HttpServer backend;
    private FrontProxy proxy;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void start() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/auth/echo", exchange -> {
            Map<String, String> seen = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> seen.put(name.toLowerCase(), String.join(",", values)));
            byte[] body = exchange.getRequestBody().readAllBytes();
            StringBuilder text = new StringBuilder(exchange.getRequestMethod() + " " + exchange.getRequestURI() + "\n");
            seen.forEach((name, value) -> text.append(name).append('=').append(value).append('\n'));
            text.append("body=").append(new String(body, StandardCharsets.UTF_8));
            byte[] response = text.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Set-Cookie", "first=1; Path=/auth");
            exchange.getResponseHeaders().add("Set-Cookie", "second=2; Path=/auth");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        backend.createContext("/auth/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://elsewhere.example/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        backend.start();
        proxy = new FrontProxy("http://127.0.0.1:" + backend.getAddress().getPort() + "/auth");
    }

    @AfterEach
    void stop() {
        proxy.close();
        backend.stop(0);
    }

    @Test
    void forwardsRequestsWithForwardedHeadersAndConfiguredHeaders() throws Exception {
        assertEquals("http://127.0.0.1:" + proxy.getPort() + "/auth", proxy.getBaseUrl());
        proxy.header("X-Client-Subject", "CN=alice").clientAddress("203.0.113.10").stripHeader("X-Untrusted");
        var response = client.send(HttpRequest.newBuilder(URI.create(proxy.getBaseUrl() + "/echo?a=b%20c"))
                .header("X-Client-Subject", "forged").header("X-Untrusted", "x").header("X-Forwarded-For", "198.51.100.1")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=alice")).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        List<String> lines = response.body().lines().toList();
        assertEquals("POST /auth/echo?a=b%20c", lines.get(0));
        assertEquals(List.of("first=1; Path=/auth", "second=2; Path=/auth"), response.headers().allValues("Set-Cookie"));
        var body = response.body();
        assertTrue(lines.contains("x-client-subject=CN=alice"), body);
        assertTrue(lines.contains("x-forwarded-for=203.0.113.10"), body);
        assertTrue(lines.contains("x-forwarded-host=127.0.0.1:" + proxy.getPort()), body);
        assertTrue(lines.contains("x-forwarded-proto=http"), body);
        assertTrue(lines.contains("x-forwarded-port=" + proxy.getPort()), body);
        assertTrue(lines.contains("content-type=application/x-www-form-urlencoded"), body);
        assertTrue(lines.contains("body=username=alice"), body);
        assertFalse(body.contains("x-untrusted"), body);
    }

    @Test
    void resetRestoresPlainForwarding() throws Exception {
        proxy.header("X-Client-Subject", "CN=alice").clientAddress("203.0.113.10");
        proxy.reset();
        var response = client.send(HttpRequest.newBuilder(URI.create(proxy.getBaseUrl() + "/echo")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        var lines = response.body().lines().toList();
        assertFalse(response.body().contains("x-client-subject"), response.body());
        assertTrue(lines.contains("x-forwarded-for=127.0.0.1"), response.body());
    }

    @Test
    void passesRedirectsThroughUnchanged() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(proxy.getBaseUrl() + "/redirect")).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(302, response.statusCode());
        assertEquals("http://elsewhere.example/target", response.headers().firstValue("Location").orElseThrow());
    }

    @Test
    void rejectsHeadersTheHttpClientCannotSend() {
        assertThrows(IllegalArgumentException.class, () -> proxy.header("Host", "example.test"));
    }

    @Test
    void terminatesTlsAndForwardsTheClientCertificate() throws Exception {
        String target = "http://127.0.0.1:" + backend.getAddress().getPort() + "/auth";
        try (var tls = new FrontProxy(target, new FrontProxy.Settings(true, FrontProxy.ClientAuth.WANT,
                "X-Client-Cert-Subject", "X-Client-Cert"))) {
            assertEquals("https://127.0.0.1:" + tls.getPort() + "/auth", tls.getBaseUrl());
            var certificate = ClientCertificate.generate("CN=alice,O=Example");
            try (var withCertificate = HttpClient.newBuilder().sslContext(tls.sslContext(certificate)).build();
                    var anonymous = HttpClient.newBuilder().sslContext(tls.sslContext()).build()) {
                var lines = withCertificate.send(HttpRequest.newBuilder(URI.create(tls.getBaseUrl() + "/echo"))
                        .header("X-Client-Cert-Subject", "CN=forged").GET().build(), HttpResponse.BodyHandlers.ofString())
                        .body().lines().toList();
                assertTrue(lines.contains("x-client-cert-subject=CN=alice,O=Example"), lines.toString());
                assertTrue(lines.contains("x-forwarded-proto=https"), lines.toString());
                assertTrue(lines.contains("x-forwarded-port=" + tls.getPort()), lines.toString());
                assertTrue(lines.stream().anyMatch(line -> line.startsWith("x-client-cert=-----BEGIN%20CERTIFICATE-----%0A")),
                        lines.toString());

                var body = anonymous.send(HttpRequest.newBuilder(URI.create(tls.getBaseUrl() + "/echo"))
                        .header("X-Client-Cert-Subject", "CN=forged").GET().build(), HttpResponse.BodyHandlers.ofString()).body();
                assertFalse(body.contains("x-client-cert"), body);
            }
        }
    }

    @Test
    void requiredClientCertificateRejectsAnonymousClients() throws Exception {
        String target = "http://127.0.0.1:" + backend.getAddress().getPort() + "/auth";
        try (var tls = new FrontProxy(target, new FrontProxy.Settings(true, FrontProxy.ClientAuth.NEED, "X-Client-Cert-Subject", ""));
                var anonymous = HttpClient.newBuilder().sslContext(tls.sslContext()).build()) {
            assertThrows(java.io.IOException.class, () -> anonymous.send(HttpRequest.newBuilder(URI.create(tls.getBaseUrl() + "/echo"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()));
        }
    }

    @Test
    void rejectsClientCertificatesWithoutTls() {
        assertThrows(IllegalArgumentException.class, () -> new FrontProxy.Settings(false, FrontProxy.ClientAuth.WANT, "", ""));
    }
}
