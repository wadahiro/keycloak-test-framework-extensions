package io.github.wadahiro.keycloak.testframework.runtimemodern;

import java.io.File;
import java.nio.file.Path;



/** Like the v15 TestPlatform, without registering process-wide shutdown hooks. */
public class EmbeddedPlatform ${runtime.platform.interface} {
    static Path directory;
    static Runnable shutdown;

    public void onStartup(Runnable hook) {
        hook.run();
    }

    public void onShutdown(Runnable hook) {
        shutdown = hook;
    }

    public void exit(Throwable cause) {
        throw new IllegalStateException("Embedded Keycloak bootstrap failed", cause);
    }

    public String name() { return "undertow"; }

    public ClassLoader getScriptEngineClassLoader(org.keycloak.Config.Scope config) { return getClass().getClassLoader(); }

    public File getTmpDirectory() {
        return directory.toFile();
    }
}
