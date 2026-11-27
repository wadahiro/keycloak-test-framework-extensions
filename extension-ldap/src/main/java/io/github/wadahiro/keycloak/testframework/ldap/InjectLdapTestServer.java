package io.github.wadahiro.keycloak.testframework.ldap;

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

    /**
     * Schema files merged into UnboundID's standard schema before the server starts.
     *
     * <p>Each value is a classpath resource path, or a file system path prefixed with {@code file:}.
     * A {@code .ldif} file contains schema entries: a subschema entry with {@code attributeTypes} and
     * {@code objectClasses}, or OpenLDAP {@code cn=config} entries with {@code olcAttributeTypes} and
     * {@code olcObjectClasses}. A {@code .schema} file uses the OpenLDAP {@code slapd.conf} schema syntax.
     * See {@link LdapSchemaLoader} for the supported directives.
     */
    String[] schema() default {};

    /**
     * Maintains an operational, client-read-only {@code memberOf} attribute on member entries, like the
     * OpenLDAP memberof overlay. Disabled by default.
     */
    boolean memberOf() default false;

    /** Group attributes whose DN values define the membership that {@link #memberOf()} reflects. */
    String[] memberOfMembershipAttributes() default {"member"};
}
