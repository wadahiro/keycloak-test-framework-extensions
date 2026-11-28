package io.github.wadahiro.keycloak.testframework.runtime15;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.KeycloakDeploymentInfo;
import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.ProviderManager;
import org.keycloak.provider.ProviderManagerRegistry;
import org.keycloak.provider.Spi;

/**
 * Deploys the test server's provider dependencies the way WildFly deploys a provider JAR present at startup.
 *
 * <p>Each dependency or provider file becomes one {@link ProviderManager} (an EAR's module and library JARs form one)
 * registered with {@link ProviderManagerRegistry} before Keycloak boots. Keycloak 15 loads its built-in providers first and then these pre-boot deployments, so a dependency's
 * factory replaces a built-in factory with the same SPI and provider id. A dependency's themes
 * ({@code META-INF/keycloak-themes.json}) and theme resources ({@code theme-resources/}) are deployed with it.
 */
final class ProviderDeployment {
    static final String LOG_PREFIX = "KCTF_PROVIDER ";
    private static ProviderDeployment current;

    private final ClassLoader loader;
    private final List<ProviderManager> managers = new ArrayList<>();

    private ProviderDeployment(List<String> names, List<List<Path>> roots) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (List<Path> deployment : roots) for (Path path : deployment) urls.add(path.toUri().toURL());
        ClassLoader runtime = ProviderDeployment.class.getClassLoader();
        // One loader holds every dependency, so a provider can use classes from another dependency it declares.
        loader = new ProviderClassLoader("kctf-providers", urls.toArray(new URL[0]), runtime);
        Set<String> used = new HashSet<>();
        for (int i = 0; i < roots.size(); i++) {
            List<Path> deployment = roots.get(i);
            String name = names.get(i);
            for (int n = 2; !used.add(name); n++) name = names.get(i) + "-" + n;
            List<URL> own = new ArrayList<>();
            for (Path path : deployment) own.add(path.toUri().toURL());
            // Reads only this deployment's registrations and themes; its classes resolve through the shared loader.
            ClassLoader registrations = new ProviderClassLoader(name, own.toArray(new URL[0]), loader);
            KeycloakDeploymentInfo info = KeycloakDeploymentInfo.create().name(name).services();
            boolean themes = contains(deployment, "META-INF/keycloak-themes.json");
            boolean themeResources = contains(deployment, "theme-resources/");
            if (themes) info.themes();
            if (themeResources) info.themeResources();
            managers.add(new ProviderManager(info, registrations));
            StringBuilder paths = new StringBuilder();
            for (Path path : deployment) paths.append(paths.length() == 0 ? "" : File.pathSeparator).append(path);
            System.out.println(LOG_PREFIX + "DEPLOY name=" + name + " themes=" + themes + " themeResources=" + themeResources + " path=" + paths);
        }
    }

    /**
     * Registers the deployments listed by the parent process, if any; call before Keycloak boots. Each line is one
     * deployment: its name and root paths separated by tabs (an EAR has several roots), or a single path.
     */
    static synchronized void deployListed() throws IOException {
        String list = System.getProperty("kctf.provider.dependencies");
        if (list == null || current != null) return;
        List<String> names = new ArrayList<>();
        List<List<Path>> roots = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(list), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\t");
            List<Path> deployment = new ArrayList<>();
            if (fields.length == 1) {
                deployment.add(Path.of(fields[0]));
                names.add(deployment.get(0).getFileName().toString());
            } else {
                for (int i = 1; i < fields.length; i++) deployment.add(Path.of(fields[i]));
                names.add(fields[0]);
            }
            roots.add(deployment);
        }
        if (roots.isEmpty()) return;
        current = new ProviderDeployment(names, roots);
        for (ProviderManager manager : current.managers) ProviderManagerRegistry.SINGLETON.deploy(manager);
    }

    static synchronized ClassLoader loader() { return current == null ? null : current.loader; }

    /** Logs each built-in provider that a dependency replaced, after Keycloak has booted. */
    static synchronized void logOverrides(KeycloakSessionFactory factory) {
        if (current == null) return;
        for (ProviderManager manager : current.managers) {
            for (var entry : manager.getLoadedFactories().entrySet()) {
                Class<? extends Provider> providerClass = entry.getKey();
                Spi spi = factory.getSpi(providerClass);
                if (spi == null) continue;
                for (ProviderFactory deployed : entry.getValue()) {
                    if (factory.getProviderFactory(providerClass, deployed.getId()) != deployed) continue;
                    String replaced = builtIn(spi, deployed.getId());
                    if (replaced != null) {
                        System.out.println(LOG_PREFIX + "OVERRIDE spi=" + spi.getName() + " id=" + deployed.getId()
                                + " provider=" + deployed.getClass().getName() + " replaces=" + replaced
                                + " deployment=" + manager.getInfo().getName());
                    }
                }
            }
        }
        System.out.flush();
    }

    private static String builtIn(Spi spi, String id) {
        try {
            for (ProviderFactory candidate : ServiceLoader.load(spi.getProviderFactoryClass(), ProviderDeployment.class.getClassLoader())) {
                if (id.equals(candidate.getId())) return candidate.getClass().getName();
            }
        } catch (ServiceConfigurationError | RuntimeException ignored) {
            // A built-in that cannot be instantiated outside Keycloak's boot is not reported.
        }
        return null;
    }

    private static boolean contains(List<Path> paths, String entry) throws IOException {
        for (Path path : paths) if (contains(path, entry)) return true;
        return false;
    }

    private static boolean contains(Path path, String entry) throws IOException {
        if (Files.isDirectory(path)) return Files.exists(path.resolve(entry));
        try (JarFile jar = new JarFile(path.toFile())) {
            if (!entry.endsWith("/")) return jar.getEntry(entry) != null;
            return jar.stream().anyMatch(e -> e.getName().startsWith(entry));
        }
    }
}
