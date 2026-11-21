package io.github.wadahiro.keycloak.ldap;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.keycloak.testframework.injection.LifeCycle;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InjectLdapTestServer {
    LifeCycle lifecycle() default LifeCycle.CLASS;

    String baseDn() default "dc=example,dc=test";

    String bindDn() default "cn=admin,dc=example,dc=test";

    String bindPassword() default "test-password";
}
