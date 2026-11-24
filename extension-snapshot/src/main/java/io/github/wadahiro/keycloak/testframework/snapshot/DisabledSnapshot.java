package io.github.wadahiro.keycloak.testframework.snapshot;

/** Internal placeholder used when the selected adapter is active but a test did not opt in. */
public final class DisabledSnapshot implements Snapshot {
    @Override public void capture() {
        throw new IllegalStateException("Snapshot operations require @ResetKeycloakState or an @InjectSnapshot field");
    }

    @Override public void restore() {
        throw new IllegalStateException("Snapshot operations require @ResetKeycloakState or an @InjectSnapshot field");
    }

    @Override public double getLastRestoreMillis() {
        return 0;
    }
}
