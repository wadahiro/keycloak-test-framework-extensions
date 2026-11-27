package io.github.wadahiro.keycloak.testframework.proxy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.keycloak.testframework.injection.LifeCycle;

/** Injects a {@link FrontProxy} that forwards to the managed Keycloak server. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InjectFrontProxy {
    LifeCycle lifecycle() default LifeCycle.CLASS;
    String ref() default "";
}
