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

    public static class ProviderServerConfig implements KeycloakServerConfig {
        @Override
        public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder builder) {
            return builder.dependency("io.github.wadahiro.keycloak.testframework", System.getProperty("quarkus.spi.fixture.artifact"));
        }
    }
}
