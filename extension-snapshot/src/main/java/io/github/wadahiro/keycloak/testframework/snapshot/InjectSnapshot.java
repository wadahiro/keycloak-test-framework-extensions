package io.github.wadahiro.keycloak.testframework.snapshot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/** Injects the selected runtime's snapshot implementation and restores it after each test method. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@ExtendWith(SnapshotSetupExtension.class)
public @interface InjectSnapshot {
}
