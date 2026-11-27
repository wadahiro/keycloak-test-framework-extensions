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

    /** Listen on HTTPS with a self-signed certificate generated for this proxy, and forward {@code X-Forwarded-Proto: https}. */
    boolean tls() default false;

    /** Whether to request a client certificate; requires {@link #tls()}. */
    FrontProxy.ClientAuth clientAuth() default FrontProxy.ClientAuth.NONE;

    /** Request header that carries the presented client certificate's subject DN (RFC 2253); empty forwards none. */
    String clientCertificateSubjectHeader() default "";

    /** Request header that carries the presented client certificate as URL-encoded PEM; empty forwards none. */
    String clientCertificateHeader() default "";
}
