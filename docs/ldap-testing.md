# LDAP federation tests

The `keycloak-test-framework-ldap` artifact provides a loopback LDAP server backed by UnboundID's in-memory directory implementation.
It is intended for tests of Keycloak's built-in LDAP User Federation provider and LDAP storage mappers.
It is not a replacement for interoperability tests against Active Directory or another production directory.

## Add the test dependency

Add this artifact to the integration-test module, alongside `keycloak-test-framework-quarkus`:

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-ldap</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

The LDAP server runs in the test JVM on a dynamic loopback port.
Keycloak runs in its managed child JVM and connects to that port over LDAP.
This setup is for a process-managed Keycloak on the same host; a remote or containerized Keycloak cannot reach the test JVM's loopback address.

## Configure a test directory

Inject `LdapTestServer`, then seed it with LDIF entries using its UnboundID `directory()` handle:

```java
@KeycloakIntegrationTest
@ResetKeycloakState
public class LdapLoginIT {
    @InjectRealm
    ManagedRealm realm;

    @InjectLdapTestServer
    LdapTestServer ldap;

    @TestSetup
    public void configureDirectoryAndFederation() throws Exception {
        ldap.directory().add(
                "dn: dc=example,dc=test",
                "objectClass: top",
                "objectClass: domain",
                "dc: example");
        ldap.directory().add(
                "dn: ou=people,dc=example,dc=test",
                "objectClass: organizationalUnit",
                "ou: people");
        ldap.directory().add(
                "dn: uid=alice,ou=people,dc=example,dc=test",
                "objectClass: inetOrgPerson",
                "cn: Alice Example",
                "sn: Example",
                "uid: alice",
                "userPassword: alice-password");

        // Create the LDAP user-storage component and its LDAPStorageMapper here.
        // Use ldap.getConnectionUrl(), getBindDn(), getBindPassword(), and getBaseDn().
        // The post-@TestSetup LDAP and Keycloak state is captured automatically.
    }
}
```

The complete working configuration and login assertions are in [`LdapFederationIT`](../quarkus-integration-tests/src/test/java/io/github/wadahiro/keycloak/testframework/quarkus/LdapFederationIT.java).
The example uses an LDAP user-attribute mapper to expose `departmentNumber` as the local `department` attribute, then authenticates the user through a real browser login.

## Coordinated state reset

`LdapTestServer` participates in `Snapshot` automatically.
The baseline includes LDAP directory data and Keycloak's database state after `@TestSetup` completes.
If you inject `@InjectSnapshot Snapshot`, calling `snapshot.capture()` manually replaces both the directory and database baselines.
After each test method, the LDAP snapshot is restored before Keycloak's H2 restore clears Keycloak caches.
This keeps external LDAP entries, the LDAP provider and mapper configuration, and imported local users on the same baseline.

Use CLASS lifecycle for the LDAP server and other fixtures when using snapshot reset.
The default LDAP server lifecycle is CLASS.
Do not run tests in parallel against the shared Keycloak server.

LDAP server configuration and listener settings are fixed for the life of the injected server; snapshots capture directory data, not server configuration.
The helper currently uses plain LDAP/simple bind and does not emulate Active Directory-specific behavior, TLS, StartTLS, or SASL.

See [snapshot lifecycle](snapshot.md) for snapshot setup and reset boundaries.
