package io.github.wadahiro.keycloak.testframework.undertow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import io.github.wadahiro.keycloak.testframework.providers.ProviderFiles;
import org.junit.jupiter.api.Test;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An EAR built by a sibling module (a provider JAR module plus the library JAR it uses in {@code lib/}) is deployed as
 * one deployment, after the {@code dependency(...)} artifacts, so its provider replaces theirs with the same id.
 */
@KeycloakIntegrationTest(config = ProviderFileEarIT.EarFileConfig.class)
class ProviderFileEarIT {
    @InjectRealm
    ManagedRealm realm;

    @Test
    void deploysEarModuleWithItsLibraryAfterMavenDependencies() throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(realm.getBaseUrl() + "/custom-test-spi/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("ear-provider-loaded ear-library-loaded", response.body());

        List<String> deployments = serverLog(realm).filter(line -> line.startsWith("KCTF_PROVIDER DEPLOY ")).toList();
        assertEquals(2, deployments.size(), deployments.toString());
        assertTrue(deployments.get(0).startsWith("KCTF_PROVIDER DEPLOY name=undertow-spi-fixture-"), deployments.toString());
        String ear = deployments.get(1);
        assertTrue(ear.startsWith("KCTF_PROVIDER DEPLOY name=undertow-ear-fixture-") && ear.contains(".ear "), ear);
        assertTrue(ear.contains("undertow-ear-fixture-provider-") && ear.contains("/lib/undertow-ear-fixture-library-"), ear);
    }

    /** The log of the child JVM serving this realm, found by the readiness line naming its URL. */
    static Stream<String> serverLog(ManagedRealm realm) throws IOException {
        String ready = "KCTF_CONTROL READY " + realm.getBaseUrl().replaceFirst("/realms/.*$", "");
        Path work = Path.of(System.getProperty("kc.test.server.extension.undertow.work"));
        try (Stream<Path> runs = Files.list(work)) {
            Path log = runs.map(run -> run.resolve("server.log")).filter(Files::isRegularFile)
                    .filter(file -> lines(file).anyMatch(ready::equals))
                    .max(Comparator.comparing(file -> file.toFile().lastModified())).orElseThrow();
            return lines(log);
        }
    }

    private static Stream<String> lines(Path file) {
        try {
            return Files.readAllLines(file).stream();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static class EarFileConfig implements KeycloakServerConfig {
        @Override
        public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder builder) {
            // Added first, deployed last: provider files always follow the dependency(...) artifacts.
            return ProviderFiles.add(builder, "../undertow-ear-fixture/ear/target/undertow-ear-fixture-*.ear")
                    .dependency("io.github.wadahiro.keycloak.testframework", "undertow-spi-fixture");
        }
    }
}
