package io.github.wadahiro.keycloak.testframework.snapshot;

/** Test-facing API for capturing and restoring the test environment baseline. */
public interface Snapshot {
    /** Replaces the single baseline used by automatic cleanup and later restore calls. */
    void capture();

    /** Restores the current baseline without consuming it, so it can be restored again. */
    void restore();

    /** Returns the duration of the most recent complete restore operation in milliseconds. */
    double getLastRestoreMillis();
}
