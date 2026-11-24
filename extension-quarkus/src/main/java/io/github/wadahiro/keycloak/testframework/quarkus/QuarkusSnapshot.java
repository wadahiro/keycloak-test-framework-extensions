package io.github.wadahiro.keycloak.testframework.quarkus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.wadahiro.keycloak.testframework.snapshot.Snapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.SnapshotParticipant;
import org.keycloak.testframework.injection.ManagedTestResource;

/** Test-process handle for snapshot commands executed by the Quarkus child process. */
final class QuarkusSnapshot extends ManagedTestResource implements AutoCloseable, Snapshot {
    private final QuarkusProcessServer server;
    private final String lease = UUID.randomUUID().toString();
    private final List<SnapshotParticipant> participants = new ArrayList<>();
    private boolean captured;
    private boolean closed;
    private double lastRestoreMillis;

    QuarkusSnapshot(QuarkusProcessServer server) { this.server = server; }

    /** Replaces the single baseline used by automatic cleanup and later restore calls. */
    public synchronized void capture() {
        checkOpen();
        for (SnapshotParticipant participant : participants) participant.capture();
        if (captured) server.command("RELEASE", lease);
        captured = false;
        server.command("CAPTURE", lease);
        captured = true;
    }

    /** Restores the current baseline without consuming it, so it can be restored again. */
    public synchronized void restore() {
        checkOpen();
        if (!captured) throw new IllegalStateException("No H2 baseline has been captured");
        long started = System.nanoTime();
        for (SnapshotParticipant participant : participants) participant.restore();
        server.command("RESTORE", lease);
        lastRestoreMillis = (System.nanoTime() - started) / 1e6;
    }

    public synchronized double getLastRestoreMillis() { return lastRestoreMillis; }
    synchronized void addParticipant(SnapshotParticipant participant) {
        checkOpen();
        if (participants.contains(participant)) return;
        if (captured) throw new IllegalStateException("Snapshot participants must be registered before first capture");
        participants.add(participant);
    }

    synchronized void initialize() { if (!captured) capture(); }
    @Override public synchronized void runCleanup() { if (captured) restore(); }
    @Override public void dirty() { throw new UnsupportedOperationException("Use capture() to replace the H2 baseline"); }

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
