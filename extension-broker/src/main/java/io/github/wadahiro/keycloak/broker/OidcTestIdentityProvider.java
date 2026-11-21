package io.github.wadahiro.keycloak.broker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A local OIDC provider for deterministic Keycloak broker tests. */
public final class OidcTestIdentityProvider implements AutoCloseable {
    private final HttpServer server;
    private final String baseUrl;
    private final String issuer;
    private final KeyPair keys;
    private final AtomicReference<OidcIdentity> nextIdentity = new AtomicReference<>(
            OidcIdentity.subject("external-user").claim("preferred_username", "external-user")
                    .claim("email", "external@example.test").claim("email_verified", true));
    private final AtomicReference<Boolean> denyNext = new AtomicReference<>(false);
    private final Map<String, Authorization> codes = new ConcurrentHashMap<>();
    private final Map<String, OidcIdentity> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, String> clientSecrets = new ConcurrentHashMap<>();

    OidcTestIdentityProvider() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keys = generator.generateKeyPair();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            baseUrl = "http://127.0.0.1:" + port;
            issuer = baseUrl + "/oidc";
            server.createContext("/oidc/.well-known/openid-configuration", this::discovery);
            server.createContext("/oidc/authorize", this::authorize);
            server.createContext("/oidc/token", this::token);
            server.createContext("/oidc/userinfo", this::userInfo);
            server.createContext("/oidc/jwks", this::jwks);
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start local OIDC test identity provider", e);
        }
    }

    public String getIssuer() { return issuer; }
    public String getAuthorizationUrl() { return issuer + "/authorize"; }
    public String getTokenUrl() { return issuer + "/token"; }
    public String getUserInfoUrl() { return issuer + "/userinfo"; }
    public String getJwksUrl() { return issuer + "/jwks"; }

    public Map<String, String> keycloakConfig(String clientId, String clientSecret) {
        clientSecrets.put(clientId, clientSecret);
        Map<String, String> config = new LinkedHashMap<>();
        config.put("clientId", clientId);
        config.put("clientSecret", clientSecret);
        config.put("clientAuthMethod", "client_secret_post");
        config.put("issuer", issuer);
        config.put("authorizationUrl", getAuthorizationUrl());
        config.put("tokenUrl", getTokenUrl());
        config.put("userInfoUrl", getUserInfoUrl());
        config.put("jwksUrl", getJwksUrl());
        config.put("useJwksUrl", "true");
        config.put("defaultScope", "openid profile email");
        return Map.copyOf(config);
    }

    /** Supplies the external account returned on the next authorization request. */
    public void nextLogin(OidcIdentity identity) {
        nextIdentity.set(identity);
        denyNext.set(false);
    }

    /** Returns access_denied on the next authorization request. */
    public void denyNextLogin() {
        denyNext.set(true);
    }

    private void discovery(HttpExchange exchange) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer);
        metadata.put("authorization_endpoint", getAuthorizationUrl());
        metadata.put("token_endpoint", getTokenUrl());
        metadata.put("userinfo_endpoint", getUserInfoUrl());
        metadata.put("jwks_uri", getJwksUrl());
        metadata.put("response_types_supported", new String[] { "code" });
        metadata.put("subject_types_supported", new String[] { "public" });
        metadata.put("id_token_signing_alg_values_supported", new String[] { "RS256" });
        metadata.put("grant_types_supported", new String[] { "authorization_code" });
        metadata.put("token_endpoint_auth_methods_supported", new String[] { "client_secret_post", "client_secret_basic" });
        json(exchange, 200, metadata);
    }

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
        String redirectUri = query.get("redirect_uri");
        String state = query.get("state");
        String clientId = query.get("client_id");
        if (redirectUri == null || state == null || clientId == null || !clientSecrets.containsKey(clientId)
                || !"code".equals(query.get("response_type"))) {
            respond(exchange, 400, "Invalid authorization request");
            return;
        }
        String location;
        if (denyNext.getAndSet(false)) {
            location = redirectUri + (redirectUri.contains("?") ? "&" : "?") + "error=access_denied&state=" + enc(state);
        } else {
            String code = UUID.randomUUID().toString();
            OidcIdentity identity = nextIdentity.getAndSet(null);
            if (identity == null) identity = OidcIdentity.subject("external-user");
            codes.put(code, new Authorization(clientId, redirectUri, query.get("nonce"), identity));
            location = redirectUri + (redirectUri.contains("?") ? "&" : "?") + "code=" + enc(code) + "&state=" + enc(state);
        }
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "POST required");
            return;
        }
        Map<String, String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String code = fields.get("code");
        Authorization authorization = code == null ? null : codes.get(code);
        String clientId = fields.get("client_id");
        String expectedSecret = clientId == null ? null : clientSecrets.get(clientId);
        String providedSecret = fields.get("client_secret");
        String authorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (providedSecret == null && authorizationHeader != null && authorizationHeader.startsWith("Basic ")) {
            String decoded = new String(Base64.getDecoder().decode(authorizationHeader.substring(6)), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator >= 0) {
                clientId = URLDecoder.decode(decoded.substring(0, separator), StandardCharsets.UTF_8);
                providedSecret = URLDecoder.decode(decoded.substring(separator + 1), StandardCharsets.UTF_8);
                expectedSecret = clientSecrets.get(clientId);
            }
        }
        if (!"authorization_code".equals(fields.get("grant_type")) || authorization == null
                || !authorization.redirectUri().equals(fields.get("redirect_uri"))
                || !authorization.clientId().equals(clientId)) {
            json(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        if (expectedSecret == null || !expectedSecret.equals(providedSecret)) {
            json(exchange, 401, Map.of("error", "invalid_client"));
            return;
        }
        if (!codes.remove(code, authorization)) {
            json(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        String accessToken = UUID.randomUUID().toString();
        accessTokens.put(accessToken, authorization.identity());
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = claims(authorization, accessToken, now);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", 300);
        response.put("id_token", signJwt(claims));
        json(exchange, 200, response);
    }

    private void userInfo(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        OidcIdentity identity = token == null ? null : accessTokens.get(token);
        if (identity == null) {
            json(exchange, 401, Map.of("error", "invalid_token"));
            return;
        }
        Map<String, Object> claims = new LinkedHashMap<>(identity.claims());
        claims.put("sub", identity.subject());
        json(exchange, 200, claims);
    }

    private void jwks(HttpExchange exchange) throws IOException {
        var rsa = (java.security.interfaces.RSAPublicKey) keys.getPublic();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", "keycloak-test-broker-1");
        jwk.put("n", Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(rsa.getModulus().toByteArray())));
        jwk.put("e", Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(rsa.getPublicExponent().toByteArray())));
        json(exchange, 200, Map.of("keys", new Object[] { jwk }));
    }

    private Map<String, Object> claims(Authorization authorization, String accessToken, long now) {
        Map<String, Object> claims = new LinkedHashMap<>(authorization.identity().claims());
        claims.put("iss", issuer);
        claims.put("sub", authorization.identity().subject());
        claims.put("aud", authorization.clientId());
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("auth_time", now);
        if (authorization.nonce() != null) claims.put("nonce", authorization.nonce());
        return claims;
    }

    private String signJwt(Map<String, Object> claims) {
        try {
            String header = b64(json(Map.of("alg", "RS256", "typ", "JWT", "kid", "keycloak-test-broker-1")));
            String payload = b64(json(claims));
            String input = header + "." + payload;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keys.getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign test ID token", e);
        }
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> result = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) return result;
        for (String pair : encoded.split("&")) {
            String[] keyValue = pair.split("=", 2);
            result.put(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8),
                    keyValue.length == 2 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) return "\"" + escape(string) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(json(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> values) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object element : values) {
                if (!first) out.append(',');
                first = false;
                out.append(json(element));
            }
            return out.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                if (i > 0) out.append(',');
                out.append(json(java.lang.reflect.Array.get(value, i)));
            }
            return out.append(']').toString();
        }
        return json(value.toString());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t");
    }

    private static byte[] unsigned(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @Override public void close() { server.stop(0); }

    private record Authorization(String clientId, String redirectUri, String nonce, OidcIdentity identity) { }
}
