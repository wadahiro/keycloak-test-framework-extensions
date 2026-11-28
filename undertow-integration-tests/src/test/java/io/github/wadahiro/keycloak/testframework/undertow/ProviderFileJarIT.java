package io.github.wadahiro.keycloak.testframework.undertow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import io.github.wadahiro.keycloak.testframework.providers.ProviderFiles;
import org.junit.jupiter.api.Test;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A provider JAR given as a file on disk is deployed like a dependency. */
@KeycloakIntegrationTest(config = ProviderFileJarIT.JarFileConfig.class)
class ProviderFileJarIT {
    @InjectRealm
    ManagedRealm realm;

    @Test
    void deploysProviderJarFromFile() throws Exception {
        assertEquals("custom-undertow-spi-loaded", get("/custom-test-spi/status"));
        assertEquals("custom-spi-provider-loaded", get("/custom-test-spi/custom-spi"));
        assertTrue(ProviderFileEarIT.serverLog(realm).anyMatch(line -> line.startsWith("KCTF_PROVIDER DEPLOY name=undertow-spi-fixture-"
                + System.getProperty("fixture.version") + ".jar themes=true themeResources=true path=")), "deployment line");
    }

    private String get(String path) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(realm.getBaseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    public static class JarFileConfig implements KeycloakServerConfig {
        @Override
        public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder builder) {
            return ProviderFiles.add(builder, "../undertow-spi-fixture/target/undertow-spi-fixture-" + System.getProperty("fixture.version") + ".jar");
        }
    }
}
