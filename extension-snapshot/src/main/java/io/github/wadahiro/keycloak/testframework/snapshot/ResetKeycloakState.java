package io.github.wadahiro.keycloak.testframework.snapshot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/** Restores the Keycloak test state to the class baseline after each test method. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@ExtendWith(SnapshotSetupExtension.class)
public @interface ResetKeycloakState {
}
