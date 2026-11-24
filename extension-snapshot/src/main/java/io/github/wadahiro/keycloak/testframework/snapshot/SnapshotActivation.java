package io.github.wadahiro.keycloak.testframework.snapshot;

import java.lang.reflect.Field;

/** Detects whether a test class opted into automatic snapshot behavior. */
public final class SnapshotActivation {
    private SnapshotActivation() {
    }

    public static boolean isEnabled(Class<?> testClass) {
        if (testClass.isAnnotationPresent(ResetKeycloakState.class)) {
            return true;
        }
        for (Class<?> current = testClass; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(InjectSnapshot.class)) {
                    return true;
                }
            }
        }
        return false;
    }
}
