package io.github.wadahiro.keycloak.testframework.runtime15;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;

/**
 * Class loader for provider dependencies, like a WildFly deployment's module: classes are found parent first, so
 * Keycloak's own classes come from the runtime, but Keycloak provider registrations
 * ({@code META-INF/services/org.keycloak.*}), theme descriptors and theme files are read from the dependency only, never
 * from the runtime's Keycloak jars. Other service registrations, such as a library's, still reach the runtime.
 */
final class ProviderClassLoader extends URLClassLoader {
    static { registerAsParallelCapable(); }

    ProviderClassLoader(String name, URL[] urls, ClassLoader parent) { super(name, urls, parent); }

    static boolean local(String name) {
        return name.startsWith("META-INF/services/org.keycloak.") || name.equals("META-INF/keycloak-themes.json")
                || name.startsWith("theme/") || name.startsWith("theme-resources/");
    }

    @Override public URL getResource(String name) { return local(name) ? findResource(name) : super.getResource(name); }

    @Override public Enumeration<URL> getResources(String name) throws IOException {
        return local(name) ? findResources(name) : super.getResources(name);
    }
}
