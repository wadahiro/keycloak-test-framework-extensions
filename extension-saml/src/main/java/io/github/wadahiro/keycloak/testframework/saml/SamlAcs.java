package io.github.wadahiro.keycloak.testframework.saml;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class SamlAcs implements AutoCloseable {
    private final HttpServer server;
    private final String url;
    private final Map<String, CompletableFuture<Received>> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicReference<CompletableFuture<Received>> unsolicited = new AtomicReference<>();

    SamlAcs() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/saml/acs", this::receive);
            server.start();
            url = "http://127.0.0.1:" + server.getAddress().getPort() + "/saml/acs";
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start the SAML assertion consumer", e);
        }
    }

    String getUrl() {
        return url;
    }

    CompletableFuture<Received> register(String relayState) {
        CompletableFuture<Received> response = new CompletableFuture<>();
        pending.put(relayState, response);
        return response;
    }

    /**
     * Registers the receiver for the next response that does not answer a pending SP-initiated request,
     * as sent by IdP-initiated SSO. Only one unsolicited response can be awaited at a time.
     */
    CompletableFuture<Received> registerUnsolicited() {
        CompletableFuture<Received> response = new CompletableFuture<>();
        CompletableFuture<Received> previous = unsolicited.getAndSet(response);
        if (previous != null) {
            previous.completeExceptionally(new IllegalStateException("Superseded by a newer IdP-initiated login"));
        }
        return response;
    }

    Received await(String relayState, CompletableFuture<Received> response, long timeout, TimeUnit unit) {
        try {
            return response.get(timeout, unit);
        } catch (Exception e) {
            if (relayState != null) {
                pending.remove(relayState);
            } else {
                unsolicited.compareAndSet(response, null);
            }
            throw new IllegalStateException("Timed out waiting for SAML response at " + url, e);
        }
    }

    private void receive(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "POST required");
            return;
        }
        String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> fields = decodeForm(form);
        String relayState = fields.get("RelayState");
        String response = fields.get("SAMLResponse");
        if (relayState != null && relayState.isEmpty()) relayState = null;
        if (response == null) {
            respond(exchange, 400, "Missing SAMLResponse");
            return;
        }
        CompletableFuture<Received> future = relayState == null ? null : pending.remove(relayState);
        if (future == null) future = unsolicited.getAndSet(null);
        if (future == null) {
            respond(exchange, 400, "Unknown SAML response");
            return;
        }
        future.complete(new Received(response, relayState));
        respond(exchange, 200, "SAML response received. You may return to the test.");
    }

    private static Map<String, String> decodeForm(String form) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : form.split("&")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                fields.put(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    record Received(String samlResponse, String relayState) {}

    @Override
    public void close() {
        server.stop(0);
    }
}
