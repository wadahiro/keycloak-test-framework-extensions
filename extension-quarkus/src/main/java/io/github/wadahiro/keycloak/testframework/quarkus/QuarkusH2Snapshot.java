package io.github.wadahiro.keycloak.testframework.quarkus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.keycloak.testframework.injection.ManagedTestResource;

/** Test-process handle for snapshot commands executed by the Quarkus child process. */
public final class QuarkusH2Snapshot extends ManagedTestResource implements AutoCloseable {
    private final QuarkusProcessServer server;
    private final String lease = UUID.randomUUID().toString();
    private final List<io.github.wadahiro.keycloak.testframework.snapshot.H2SnapshotParticipant> participants = new ArrayList<>();
    private boolean captured;
    private boolean closed;
    private double lastRestoreMillis;

    QuarkusH2Snapshot(QuarkusProcessServer server) { this.server = server; }

    public synchronized void capture() {
        checkOpen();
        for (io.github.wadahiro.keycloak.testframework.snapshot.H2SnapshotParticipant participant : participants) participant.capture();
        if (captured) server.command("RELEASE", lease);
        captured = false;
        server.command("CAPTURE", lease);
        captured = true;
    }

    public synchronized void restore() {
        checkOpen();
        if (!captured) throw new IllegalStateException("No H2 baseline has been captured");
        long started = System.nanoTime();
        for (io.github.wadahiro.keycloak.testframework.snapshot.H2SnapshotParticipant participant : participants) participant.restore();
        server.command("RESTORE", lease);
        lastRestoreMillis = (System.nanoTime() - started) / 1e6;
    }

    public synchronized double getLastRestoreMillis() { return lastRestoreMillis; }
    public long getServerPid() { return server.getPid(); }

    synchronized void addParticipant(io.github.wadahiro.keycloak.testframework.snapshot.H2SnapshotParticipant participant) {
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
