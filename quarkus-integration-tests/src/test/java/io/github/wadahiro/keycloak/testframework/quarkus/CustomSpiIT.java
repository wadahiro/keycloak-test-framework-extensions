package io.github.wadahiro.keycloak.testframework.quarkus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KeycloakIntegrationTest(config = CustomSpiIT.ProviderServerConfig.class)
class CustomSpiIT {
    @InjectRealm
    ManagedRealm realm;

    @Test
    void loadsAndInvokesCustomProviderFromMavenDependency() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(realm.getBaseUrl() + "/custom-test-spi/status")).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("custom-quarkus-spi-loaded", response.body());
    }

    @Test
    void ignoresForwardedHeadersUnlessProxyHeadersIsSet() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(realm.getBaseUrl() + "/custom-test-spi/request"))
                .header("X-Forwarded-For", "203.0.113.10").header("X-Forwarded-Host", "proxy.example.test")
                .header("X-Forwarded-Port", "8443").header("X-Forwarded-Proto", "https").GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("203.0.113.10"), response.body());
        assertTrue(response.body().contains("base-uri=" + realm.getBaseUrl().replaceFirst("/realms/.*$", "/") + "\n"),
                response.body());
    }

    public static class ProviderServerConfig implements KeycloakServerConfig {
        @Override
        public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder builder) {
            return builder.dependency("io.github.wadahiro.keycloak.testframework", System.getProperty("quarkus.spi.fixture.artifact"));
        }
    }
}
