package io.github.wadahiro.keycloak.testframework.snapshot;

/** A non-database state store that participates in snapshot capture and restore. */
public interface SnapshotParticipant {
    void capture();

    void restore();
}
