package io.github.wadahiro.keycloak.undertow;

import java.util.UUID;
import org.keycloak.testframework.injection.ManagedTestResource;

/** One class-owned baseline of the whole server database, including baseline user/client sessions. */
public final class H2Snapshot extends ManagedTestResource implements AutoCloseable {
    private final UndertowProcessServer server;
    private final String lease = UUID.randomUUID().toString();
    private boolean captured;
    private boolean closed;
    private double lastRestoreMillis;

    H2Snapshot(UndertowProcessServer server) { this.server = server; }

    /** Replace the baseline, for example at the end of a Framework @TestSetup method. */
    public synchronized void capture() {
        checkOpen();
        if (captured) server.command("RELEASE", lease);
        captured = false;
        server.command("CAPTURE", lease);
        captured = true;
    }

    /** Restore immediately; normally invoked automatically after user @AfterEach methods. */
    public synchronized void restore() {
        checkOpen();
        if (!captured) throw new IllegalStateException("No H2 baseline has been captured");
        long started = System.nanoTime();
        server.command("RESTORE", lease);
        lastRestoreMillis = (System.nanoTime() - started) / 1e6;
    }

    public synchronized double getLastRestoreMillis() { return lastRestoreMillis; }
    public long getServerPid() { return server.pid(); }
    synchronized void initialize() { if (!captured) capture(); }
    @Override public void runCleanup() { restore(); }
    @Override public void dirty() { throw new UnsupportedOperationException("Use capture() to replace an H2 baseline"); }
    private void checkOpen() { if (closed) throw new IllegalStateException("H2 snapshot is closed"); }
    @Override public synchronized void close() {
        if (closed) return;
        try {
            if (captured && server.isRunning()) {
                restore();
                server.command("RELEASE", lease);
            }
        } finally { closed = true; }
    }
}
