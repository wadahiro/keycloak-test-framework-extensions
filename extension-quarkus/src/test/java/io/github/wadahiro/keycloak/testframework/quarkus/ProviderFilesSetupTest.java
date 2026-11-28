package io.github.wadahiro.keycloak.testframework.quarkus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import io.github.wadahiro.keycloak.testframework.providers.ProviderFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;
import static org.junit.jupiter.api.Assertions.*;

class ProviderFilesSetupTest {
    @TempDir Path dir;

    @Test void installsJarsAndDirectoriesAndKeepsThemOutOfServerArguments() throws Exception {
        Path jar = Files.createFile(dir.resolve("my-provider.jar"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        var config = ProviderFiles.add(KeycloakServerConfigBuilder.startDev(), jar.toString(), classes.toString());
        assertEquals(List.of(jar.toString(), classes.toString()), QuarkusProcessServer.providerSetup(config).files());
        assertTrue(QuarkusProcessServer.keycloakArgs(config, "26.8.2").stream().noneMatch(arg -> arg.contains("kctf-provider-file")));
    }

    @Test void rejectsEarArchives() throws Exception {
        Path ear = Files.createFile(dir.resolve("example.ear"));
        var config = ProviderFiles.add(KeycloakServerConfigBuilder.startDev(), ear.toString());
        var failure = assertThrows(IllegalArgumentException.class, () -> QuarkusProcessServer.providerSetup(config));
        assertTrue(failure.getMessage().startsWith("EAR provider archives are supported only by the Keycloak 15 (WildFly-based) runtime;"
                + " pass the JAR files instead: "), failure.getMessage());
    }
}
