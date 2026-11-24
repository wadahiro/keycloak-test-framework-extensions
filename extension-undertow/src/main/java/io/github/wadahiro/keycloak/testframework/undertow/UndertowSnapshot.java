package io.github.wadahiro.keycloak.testframework.undertow;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.wadahiro.keycloak.testframework.snapshot.Snapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.SnapshotParticipant;
import org.keycloak.testframework.injection.ManagedTestResource;

/** One class-owned baseline of the whole server database, including baseline user/client sessions. */
final class UndertowSnapshot extends ManagedTestResource implements AutoCloseable, Snapshot {
    private final UndertowProcessServer server;
    private final String lease = UUID.randomUUID().toString();
    private boolean captured;
    private boolean closed;
    private double lastRestoreMillis;
    private final List<SnapshotParticipant> participants = new ArrayList<>();

    UndertowSnapshot(UndertowProcessServer server) { this.server = server; }

    /** Replace the baseline, for example at the end of a Framework @TestSetup method. */
    public synchronized void capture() {
        checkOpen();
        for (SnapshotParticipant participant : participants) participant.capture();
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
        for (SnapshotParticipant participant : participants) participant.restore();
        server.command("RESTORE", lease);
        lastRestoreMillis = (System.nanoTime() - started) / 1e6;
    }

    public synchronized double getLastRestoreMillis() { return lastRestoreMillis; }
    synchronized void addParticipant(SnapshotParticipant participant) {
        checkOpen();
        if (participants.contains(participant)) return;
        if (captured) throw new IllegalStateException("Snapshot participants must be registered before the first capture");
        participants.add(participant);
    }
    @Override public void runCleanup() { if (captured) restore(); }
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
