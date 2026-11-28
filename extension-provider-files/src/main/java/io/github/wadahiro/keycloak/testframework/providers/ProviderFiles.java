package io.github.wadahiro.keycloak.testframework.providers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/**
 * Provider files on disk for the test server: provider JARs, exploded provider directories and, on the Keycloak 15
 * (WildFly-based) runtime, EAR archives. They complement {@code dependency(groupId, artifactId)} when a test must deploy
 * the exact archives that production ships, for example an EAR built by a sibling module.
 *
 * <pre>{@code
 * public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
 *     return ProviderFiles.add(config, "../example-ear/target/example-*.ear");
 * }
 * }</pre>
 *
 * <p>A relative path is resolved against the working directory, which Maven Surefire and Failsafe set to the module's
 * base directory. The last path segment may be a glob ({@code *}, {@code ?}, {@code [..]}, {@code {..}}), for archive names
 * that contain a version; it must match exactly one file. Files are deployed after every {@code dependency(...)} artifact,
 * in the order they were added.
 *
 * <p>The paths travel in the builder's server options under the reserved {@value #OPTION_PREFIX} prefix; the runtime
 * adapters remove them before they pass options to Keycloak.
 */
public final class ProviderFiles {
    /** Prefix of the server options that carry provider files; not a Keycloak option. */
    public static final String OPTION_PREFIX = "kctf-provider-file--";

    /** Kind of a resolved provider file. */
    public enum Kind {
        /** A provider JAR. */
        JAR,
        /** An exploded provider JAR, such as a module's classes directory. */
        DIRECTORY,
        /** An enterprise archive ({@code .ear} file or exploded {@code .ear} directory); Keycloak 15 runtime only. */
        EAR
    }

    /** A provider file that exists on disk. */
    public record ProviderFile(Path path, Kind kind) {
        public ProviderFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private ProviderFiles() { }

    /**
     * Adds provider files to the server configuration, after those already added.
     *
     * @param config the server configuration
     * @param paths JAR files, exploded provider directories or EAR archives; the last segment may be a glob that matches
     *        exactly one file
     * @return {@code config}, for chaining
     * @throws IllegalArgumentException if a path does not exist, a glob does not match exactly one file, or a file is not a
     *         provider JAR, directory or EAR
     */
    public static KeycloakServerConfigBuilder add(KeycloakServerConfigBuilder config, String... paths) {
        Objects.requireNonNull(config, "config");
        int next = (int) options(config).keySet().stream().filter(ProviderFiles::isOption).count();
        for (String path : paths) {
            Objects.requireNonNull(path, "path");
            String absolute = Path.of(path).toAbsolutePath().normalize().toString();
            resolve(absolute); // fail at configuration time, with the caller's stack
            config.option(OPTION_PREFIX + String.format(Locale.ROOT, "%04d", next++), absolute);
        }
        return config;
    }

    /** Adds provider files to the server configuration; see {@link #add(KeycloakServerConfigBuilder, String...)}. */
    public static KeycloakServerConfigBuilder add(KeycloakServerConfigBuilder config, Path... paths) {
        String[] values = new String[paths.length];
        for (int i = 0; i < paths.length; i++) values[i] = Objects.requireNonNull(paths[i], "path").toString();
        return add(config, values);
    }

    /** Whether a server option key carries a provider file rather than a Keycloak option. */
    public static boolean isOption(String key) { return key.startsWith(OPTION_PREFIX); }

    /**
     * The provider files of a server configuration, resolved again at server start, in the order they were added.
     * Intended for the runtime adapters.
     */
    public static List<ProviderFile> of(KeycloakServerConfigBuilder config) {
        if (config == null) return List.of();
        Map<String, String> ordered = new TreeMap<>();
        options(config).forEach((key, value) -> { if (isOption(key)) ordered.put(key, value); });
        List<ProviderFile> files = new ArrayList<>();
        for (String path : ordered.values()) files.addAll(resolve(path));
        return files;
    }

    /** Resolves one path or last-segment glob to the existing provider file it names. */
    public static List<ProviderFile> resolve(String path) {
        Path candidate = Path.of(path).toAbsolutePath().normalize();
        Path name = candidate.getFileName();
        Path directory = candidate.getParent();
        if (directory != null && isGlob(directory.toString())) {
            throw new IllegalArgumentException("Only the last path segment of a provider file may be a glob: " + path);
        }
        if (name == null || !isGlob(name.toString())) return List.of(classify(candidate));
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Provider file directory does not exist: " + directory + " (pattern " + path + ")");
        }
        PathMatcher matcher = directory.getFileSystem().getPathMatcher("glob:" + name);
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) if (matcher.matches(entry.getFileName())) matches.add(entry);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot list provider file directory " + directory, failure);
        }
        if (matches.isEmpty()) throw new IllegalArgumentException("Provider file pattern matches no file: " + path);
        if (matches.size() > 1) {
            // Usually stale builds of other versions; deploying them all would mix their classes.
            matches.sort(null);
            throw new IllegalArgumentException("Provider file pattern matches " + matches.size() + " files, expected one: " + path
                    + " -> " + matches + "; remove the stale files or add each file explicitly");
        }
        return List.of(classify(matches.get(0)));
    }

    private static ProviderFile classify(Path path) {
        if (!Files.exists(path)) throw new IllegalArgumentException("Provider file does not exist: " + path);
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Files.isDirectory(path)) return new ProviderFile(path, name.endsWith(".ear") ? Kind.EAR : Kind.DIRECTORY);
        if (name.endsWith(".jar")) return new ProviderFile(path, Kind.JAR);
        if (name.endsWith(".ear")) return new ProviderFile(path, Kind.EAR);
        throw new IllegalArgumentException("Unsupported provider file type (expected a .jar, an .ear or a directory): " + path);
    }

    private static boolean isGlob(String segment) {
        for (char c : segment.toCharArray()) if (c == '*' || c == '?' || c == '[' || c == '{') return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> options(KeycloakServerConfigBuilder config) {
        try {
            Field field = KeycloakServerConfigBuilder.class.getDeclaredField("options");
            field.setAccessible(true);
            return (Map<String, String>) field.get(config);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Keycloak Test Framework version: cannot read server options", failure);
        }
    }
}
