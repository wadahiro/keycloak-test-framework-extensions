package io.github.wadahiro.keycloak.testframework.undertow;

import java.lang.annotation.*;

/** Restore the shared H2 database after each method; use CLASS-scoped fixture resources. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InjectH2Snapshot { }
