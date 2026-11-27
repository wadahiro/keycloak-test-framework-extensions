package io.github.wadahiro.keycloak.testframework.runtime15;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProviderClassLoaderTest {
    private static final String SERVICES = "META-INF/services/org.keycloak.theme.ThemeProviderFactory";

    @Test void readsRegistrationsAndThemesFromTheDependencyOnlyAndClassesParentFirst(@TempDir Path classes) throws Exception {
        ClassLoader runtime = getClass().getClassLoader();
        assertNotNull(runtime.getResource(SERVICES), "built-in registration on the runtime classpath");
        assertNotNull(runtime.getResource("META-INF/keycloak-themes.json"), "built-in themes on the runtime classpath");
        try (var empty = new ProviderClassLoader("empty", new URL[]{classes.toUri().toURL()}, runtime)) {
            assertNull(empty.getResource(SERVICES));
            assertFalse(empty.getResources(SERVICES).hasMoreElements());
            assertNull(empty.getResourceAsStream("META-INF/keycloak-themes.json"));
            assertNotNull(empty.getResource("META-INF/keycloak-server.json"), "other resources delegate to the runtime");
            assertTrue(empty.getResources("META-INF/services/java.sql.Driver").hasMoreElements(), "library registrations delegate to the runtime");
            assertSame(org.keycloak.provider.ProviderManager.class, empty.loadClass("org.keycloak.provider.ProviderManager"));
        }

        Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(classes.resolve(SERVICES), "com.example.ThemeFactory\n");
        Files.writeString(classes.resolve("META-INF/keycloak-themes.json"), "{\"themes\":[]}");
        try (var provider = new ProviderClassLoader("classes", new URL[]{classes.toUri().toURL()}, runtime)) {
            var found = Collections.list(provider.getResources(SERVICES));
            assertEquals(1, found.size());
            assertEquals(classes.resolve(SERVICES).toUri().toURL(), found.get(0));
            assertEquals("{\"themes\":[]}", new String(provider.getResourceAsStream("META-INF/keycloak-themes.json").readAllBytes()));
        }
    }
}
