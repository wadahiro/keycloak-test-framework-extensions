package io.github.wadahiro.keycloak.testframework.providers;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import io.github.wadahiro.keycloak.testframework.providers.ProviderFiles.Kind;
import io.github.wadahiro.keycloak.testframework.providers.ProviderFiles.ProviderFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;
import static org.junit.jupiter.api.Assertions.*;

class ProviderFilesTest {
    @TempDir Path dir;

    @Test void keepsFilesInTheOrderTheyWereAdded() throws Exception {
        Path b = Files.createFile(dir.resolve("b-provider.jar"));
        Path a = Files.createFile(dir.resolve("a-provider.jar"));
        Path ear = Files.createFile(dir.resolve("example.ear"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path exploded = Files.createDirectories(dir.resolve("exploded.ear"));
        var config = KeycloakServerConfigBuilder.startDev();
        ProviderFiles.add(config, b.toString(), ear.toString());
        ProviderFiles.add(config, a, classes, exploded);
        assertEquals(List.of(new ProviderFile(b, Kind.JAR), new ProviderFile(ear, Kind.EAR), new ProviderFile(a, Kind.JAR),
                new ProviderFile(classes, Kind.DIRECTORY), new ProviderFile(exploded, Kind.EAR)), ProviderFiles.of(config));
    }

    @Test void resolvesALastSegmentGlobToItsSingleMatch() throws Exception {
        Path ear = Files.createFile(dir.resolve("example-1.0.ear"));
        Files.createFile(dir.resolve("other.jar"));
        var config = ProviderFiles.add(KeycloakServerConfigBuilder.startDev(), dir.resolve("example-*.ear").toString());
        assertEquals(List.of(new ProviderFile(ear, Kind.EAR)), ProviderFiles.of(config));
    }

    @Test void rejectsAGlobMatchingSeveralFiles() throws Exception {
        Files.createFile(dir.resolve("example-1.1.ear"));
        Files.createFile(dir.resolve("example-1.0.ear"));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ProviderFiles.add(KeycloakServerConfigBuilder.startDev(), dir.resolve("example-*.ear").toString()));
        assertTrue(failure.getMessage().startsWith("Provider file pattern matches 2 files, expected one: "), failure.getMessage());
        assertTrue(failure.getMessage().contains("example-1.0.ear, " + dir.resolve("example-1.1.ear")), failure.getMessage());
    }

    @Test void resolvesRelativePathsAgainstTheWorkingDirectory() {
        var config = ProviderFiles.add(KeycloakServerConfigBuilder.startDev(), "target/test-classes");
        assertEquals(List.of(new ProviderFile(Path.of("target/test-classes").toAbsolutePath(), Kind.DIRECTORY)), ProviderFiles.of(config));
    }

    @Test void rejectsMissingFilesUnsupportedTypesAndEmptyGlobs() throws Exception {
        var config = KeycloakServerConfigBuilder.startDev();
        var missing = assertThrows(IllegalArgumentException.class, () -> ProviderFiles.add(config, dir.resolve("missing.jar").toString()));
        assertTrue(missing.getMessage().startsWith("Provider file does not exist: "), missing.getMessage());
        Files.createFile(dir.resolve("app.war"));
        var war = assertThrows(IllegalArgumentException.class, () -> ProviderFiles.add(config, dir.resolve("app.war").toString()));
        assertTrue(war.getMessage().startsWith("Unsupported provider file type"), war.getMessage());
        var none = assertThrows(IllegalArgumentException.class, () -> ProviderFiles.add(config, dir.resolve("*.ear").toString()));
        assertTrue(none.getMessage().startsWith("Provider file pattern matches no file: "), none.getMessage());
        var nested = assertThrows(IllegalArgumentException.class, () -> ProviderFiles.add(config, dir.resolve("*/x.jar").toString()));
        assertTrue(nested.getMessage().contains("last path segment"), nested.getMessage());
        assertTrue(ProviderFiles.of(config).isEmpty());
    }

    @Test void failsAtServerStartWhenAFileDisappeared() throws Exception {
        Path jar = Files.createFile(dir.resolve("provider.jar"));
        var config = ProviderFiles.add(KeycloakServerConfigBuilder.startDev(), jar.toString());
        Files.delete(jar);
        assertThrows(IllegalArgumentException.class, () -> ProviderFiles.of(config));
    }

    @Test void carriesPathsAsReservedOptionsOnly() throws Exception {
        Path jar = Files.createFile(dir.resolve("provider.jar"));
        var config = ProviderFiles.add(KeycloakServerConfigBuilder.startDev().option("db", "dev-mem"), jar.toString());
        Field field = KeycloakServerConfigBuilder.class.getDeclaredField("options");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, String> options = (Map<String, String>) field.get(config);
        assertEquals(Map.of("db", "dev-mem", "kctf-provider-file--0000", jar.toString()), options);
        assertTrue(ProviderFiles.isOption("kctf-provider-file--0000"));
        assertFalse(ProviderFiles.isOption("db"));
    }
}
