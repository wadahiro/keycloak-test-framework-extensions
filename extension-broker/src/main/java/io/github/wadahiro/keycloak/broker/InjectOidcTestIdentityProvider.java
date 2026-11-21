package io.github.wadahiro.keycloak.broker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.keycloak.testframework.injection.LifeCycle;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InjectOidcTestIdentityProvider {
    LifeCycle lifecycle() default LifeCycle.CLASS;
    String ref() default "";
}
