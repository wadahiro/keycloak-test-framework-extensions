package io.github.wadahiro.keycloak.testframework.snapshot;

/** A non-H2 state store that participates in an H2 baseline capture and restore. */
public interface H2SnapshotParticipant {
    void capture();

    void restore();
}
