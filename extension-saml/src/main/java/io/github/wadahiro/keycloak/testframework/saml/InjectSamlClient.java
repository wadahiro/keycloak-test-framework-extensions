package io.github.wadahiro.keycloak.testframework.saml;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ClientConfig;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InjectSamlClient {
    Class<? extends ClientConfig> config() default DefaultSamlClientConfiguration.class;
    LifeCycle lifecycle() default LifeCycle.CLASS;
    String ref() default "";
    String realmRef() default "";
    String webDriverRef() default "";
}
