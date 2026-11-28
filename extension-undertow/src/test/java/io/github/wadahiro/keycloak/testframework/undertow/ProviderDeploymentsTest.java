package io.github.wadahiro.keycloak.testframework.undertow;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import io.github.wadahiro.keycloak.testframework.providers.ProviderFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;
import static org.junit.jupiter.api.Assertions.*;

class ProviderDeploymentsTest {
    @TempDir Path dir;

    @Test void expandsAnEarIntoOneDeploymentOfItsModulesAndLibraries() throws Exception {
        Path ear = zip(dir.resolve("example.ear"), Map.of(
                "META-INF/application.xml", "<application xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"8\">"
                        + "<module><ejb>provider-ejb.jar</ejb></module><module><java>a-provider.jar</java></module></application>",
                "provider-ejb.jar", "ejb", "a-provider.jar", "jar", "unlisted.jar", "ignored",
                "lib/z-library.jar", "lib", "lib/b-library.jar", "lib", "lib/nested/ignored.jar", "ignored", "lib/readme.txt", "ignored"));
        Path jar = Files.writeString(dir.resolve("my-provider.jar"), "jar");
        Path run = Files.createDirectories(dir.resolve("run"));
        var config = ProviderFiles.add(KeycloakServerConfigBuilder.startDev(), ear.toString(), jar.toString());

        Path expanded = run.resolve("provider-files/0-example.ear");
        assertEquals(List.of(String.join("\t", "example.ear", expanded.resolve("provider-ejb.jar").toString(),
                        expanded.resolve("a-provider.jar").toString(), expanded.resolve("lib/b-library.jar").toString(),
                        expanded.resolve("lib/z-library.jar").toString()), "my-provider.jar\t" + jar),
                UndertowProcessServer.providerDeployments(config, run));
        assertEquals("ejb", Files.readString(expanded.resolve("provider-ejb.jar")));
    }

    @Test void honorsTheLibraryDirectoryOfADescriptorWithoutNamespace() throws Exception {
        Path ear = Files.createDirectories(dir.resolve("exploded.ear"));
        Files.createDirectories(ear.resolve("META-INF"));
        Files.writeString(ear.resolve("META-INF/application.xml"), "<?xml version=\"1.0\"?>\n<!DOCTYPE application PUBLIC "
                + "\"-//Sun Microsystems, Inc.//DTD J2EE Application 1.3//EN\" \"http://java.sun.com/dtd/application_1_3.dtd\">\n"
                + "<application><module><java>provider.jar</java></module><library-directory>APP-INF/lib</library-directory></application>");
        Files.writeString(ear.resolve("provider.jar"), "jar");
        Files.createDirectories(ear.resolve("APP-INF/lib"));
        Files.writeString(ear.resolve("APP-INF/lib/library.jar"), "lib");
        Files.createDirectories(ear.resolve("lib"));
        Files.writeString(ear.resolve("lib/not-a-library.jar"), "ignored");
        assertEquals(List.of(ear.resolve("provider.jar"), ear.resolve("APP-INF/lib/library.jar")), EarArchive.members(ear, dir.resolve("unused")));
    }

    @Test void takesTopLevelJarsAsModulesWithoutDescriptor() throws Exception {
        Path ear = zip(dir.resolve("plain.ear"), Map.of("b.jar", "b", "a.jar", "a", "lib/library.jar", "lib"));
        Path into = dir.resolve("into");
        assertEquals(List.of(into.resolve("a.jar"), into.resolve("b.jar"), into.resolve("lib/library.jar")), EarArchive.members(ear, into));
    }

    @Test void rejectsWebModulesAndMissingModules() throws Exception {
        Path web = zip(dir.resolve("web.ear"), Map.of("META-INF/application.xml",
                "<application><module><web><web-uri>app.war</web-uri><context-root>/app</context-root></web></module></application>"));
        var failure = assertThrows(IllegalArgumentException.class, () -> EarArchive.members(web, dir.resolve("web")));
        assertTrue(failure.getMessage().contains("Unsupported EAR module type <web>"), failure.getMessage());
        Path missing = zip(dir.resolve("missing.ear"), Map.of("META-INF/application.xml",
                "<application><module><ejb>gone.jar</ejb></module></application>"));
        failure = assertThrows(IllegalArgumentException.class, () -> EarArchive.members(missing, dir.resolve("missing")));
        assertTrue(failure.getMessage().contains("gone.jar is missing"), failure.getMessage());
    }

    @Test void rejectsEntriesOutsideTheExpansionDirectory() throws Exception {
        Path ear = zip(dir.resolve("slip.ear"), Map.of("../escape.jar", "x"));
        assertThrows(IOException.class, () -> EarArchive.members(ear, dir.resolve("slip")));
        assertFalse(Files.exists(dir.resolve("escape.jar")));
    }

    @Test void skipsProviderFileOptionsWhenReadingSpiOptions() throws Exception {
        Path jar = Files.writeString(dir.resolve("p.jar"), "jar");
        var config = ProviderFiles.add(KeycloakServerConfigBuilder.startDev().spiOption("s", "p", "k", "v"), jar.toString());
        assertEquals(Map.of("s.p.k", "v"), UndertowProcessServer.spiOptions(config));
    }

    private static Path zip(Path target, Map<String, String> entries) throws IOException {
        try (OutputStream file = Files.newOutputStream(target); ZipOutputStream out = new ZipOutputStream(file)) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return target;
    }
}
