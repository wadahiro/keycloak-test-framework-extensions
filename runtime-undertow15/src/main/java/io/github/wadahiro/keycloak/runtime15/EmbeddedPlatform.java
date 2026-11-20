package io.github.wadahiro.keycloak.runtime15;

import java.io.File;
import java.nio.file.Path;

import org.keycloak.platform.PlatformProvider;

/** Like the v15 TestPlatform, without registering process-wide shutdown hooks. */
public class EmbeddedPlatform implements PlatformProvider {
    static Path directory;
    static Runnable shutdown;

    @Override
    public void onStartup(Runnable hook) {
        hook.run();
    }

    @Override
    public void onShutdown(Runnable hook) {
        shutdown = hook;
    }

    @Override
    public void exit(Throwable cause) {
        throw new IllegalStateException("Embedded Keycloak bootstrap failed", cause);
    }

    @Override
    public File getTmpDirectory() {
        return directory.toFile();
    }
}
