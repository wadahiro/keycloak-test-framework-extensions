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
    /**
     * Configuration of the temporary SAML client created for the test.
     * It must stay at its default when {@link #clientId()} selects an existing client.
     */
    Class<? extends ClientConfig> config() default DefaultSamlClientConfiguration.class;

    /**
     * Client ID (SAML entity ID) of an existing SAML client in the realm, for example one imported from realm JSON.
     * When set, no client is created: the client's POST assertion consumer URL is temporarily pointed at the
     * test's local ACS, which is also added to its redirect URIs, and both are restored when the injected value
     * is closed. All other client settings are used as configured.
     */
    String clientId() default "";

    LifeCycle lifecycle() default LifeCycle.CLASS;
    String ref() default "";
    String realmRef() default "";
    String webDriverRef() default "";
}
