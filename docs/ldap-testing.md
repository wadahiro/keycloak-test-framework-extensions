# LDAP federation tests

The `keycloak-test-framework-ldap` artifact provides a loopback LDAP server backed by UnboundID's in-memory directory implementation.
It is intended for tests of Keycloak's built-in LDAP User Federation provider and LDAP storage mappers.
It is not a replacement for interoperability tests against Active Directory or another production directory.

## Add the test dependency

Add this artifact to the integration-test module, alongside `keycloak-test-framework-quarkus`.
Its version is managed by the extensions BOM described in [Getting started](getting-started.md).

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-ldap</artifactId>
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

## Custom schema

The server starts with UnboundID's standard schema and enforces it: an entry with an attribute that none of its object classes allows is rejected.
To test mappers on attributes from your own schema, list schema files in `schema`; they are merged into the standard schema before the server starts.

```java
@InjectLdapTestServer(schema = "ldap/x-example.schema")
LdapTestServer ldap;
```

Each value is a classpath resource, such as `src/test/resources/ldap/x-example.schema`, or a file system path prefixed with `file:`.
The file extension selects the format:

| Extension | Content |
| --- | --- |
| `.ldif` | A subschema entry with `attributeTypes` and `objectClasses` (RFC 4512), or OpenLDAP `cn=config` schema entries with `olcAttributeTypes`, `olcObjectClasses`, and `olcObjectIdentifier`. Entry and `changetype: modify` records are read; `{N}` ordering prefixes are removed. |
| `.schema` | OpenLDAP `slapd.conf` schema syntax: `attributetype`, `objectclass`, `objectidentifier`, `ldapsyntax`, and `ditcontentrule` directives, `#` comment lines, and indented continuation lines. |

An OpenLDAP schema file can be used as is:

```text
# 1.3.6.1.4.1.32473 is the example enterprise number reserved for documentation (RFC 5612).
objectidentifier xExampleRoot 1.3.6.1.4.1.32473.1

attributetype ( xExampleRoot:1.1
    NAME 'x-example-badgeNumber'
    DESC 'Badge number of a person'
    EQUALITY caseIgnoreMatch
    SYNTAX 1.3.6.1.4.1.1466.115.121.1.15{64}
    SINGLE-VALUE )

objectclass ( xExampleRoot:2.1
    NAME 'x-example-person'
    SUP top AUXILIARY
    MAY x-example-badgeNumber )
```

Seed users with the auxiliary class, and list it in the LDAP provider's `userObjectClasses` so that users created by Keycloak get it too:

```java
ldap.directory().add("dn: uid=alice,ou=people,dc=example,dc=test",
        "objectClass: inetOrgPerson", "objectClass: x-example-person",
        "cn: Alice Example", "sn: Example", "uid: alice", "x-example-badgeNumber: B-1001");
```

A `user-attribute-ldap-mapper` with `ldap.attribute=x-example-badgeNumber` then imports the value.
With `editMode=WRITABLE` on the provider and `read.only=false` on the mapper, changes made in Keycloak are written back to the directory.

OID macros (`objectidentifier`, `olcObjectIdentifier`) are expanded in the OID position and in `SYNTAX`, including the `name:suffix` form.
Every definition is parsed strictly, so a malformed definition fails the test with the file name instead of being dropped.
`LdapSchemaLoader` exposes the same parsing for use outside the annotation.

Limits:

- `include` and other `slapd.conf` directives are rejected; inline the referenced schema instead.
- Matching rules and syntaxes that UnboundID does not implement are accepted in definitions, but values are then compared with a fallback rule chosen by UnboundID.
- Schema is fixed when the server starts; it cannot be changed through LDAP modifications of `cn=schema`.
- A later file overrides an earlier definition with the same OID; do not reuse a standard attribute name under a different OID.

## memberOf emulation

Directories such as OpenLDAP with the memberof overlay and Active Directory maintain `memberOf` on user entries from group membership.
Enable the same behavior with `memberOf = true`:

```java
@InjectLdapTestServer(schema = "ldap/x-example.schema", memberOf = true)
LdapTestServer ldap;
```

With the option enabled:

- `memberOf` on an entry lists the DNs of the groups whose membership attribute contains the entry's DN.
  The membership attribute is `member` by default, like the OpenLDAP overlay; set `memberOfMembershipAttributes`, for example to `{"member", "uniqueMember"}`, to follow others.
  The optional `#'…'B` UID suffix of `uniqueMember` values is ignored.
- Changes made by LDAP clients, including Keycloak, update `memberOf` after each successful add, modify, delete, modify DN, and transaction.
  A group rename moves the value to the new DN, and a deleted group disappears from its members' `memberOf`.
- `memberOf` is an operational attribute (`USAGE dSAOperation`, `NO-USER-MODIFICATION`), as with the OpenLDAP overlay.
  It is returned only when requested by name or with `+`, it can be used in search filters, and client writes fail with `constraintViolation`.
  The definition is added unless one of your schema files already defines `memberOf`; if you define it yourself, keep it operational, because a user attribute that no object class allows makes the recompute, and therefore client searches, fail.
- Each client search or compare first scans the directory to recompute `memberOf`; this suits test-sized directories, not bulk data sets.
- Memberships are direct only; nested groups are not expanded.
- Group values are not updated when a member entry is renamed or deleted; that is referential-integrity behavior, not memberOf behavior.
  Values that are not DNs or that name missing entries are ignored.

Writes through `ldap.directory()` bypass the LDAP listener.
For them, `memberOf` is recomputed before the next LDAP client search or compare, when the snapshot baseline is captured, and when you call `ldap.synchronizeMemberOf()`.
Call `synchronizeMemberOf()` before reading `memberOf` through `ldap.directory()` after a direct group change.

Keycloak's `group-ldap-mapper` reads these values with `user.roles.retrieve.strategy=GET_GROUPS_FROM_USER_MEMBEROF_ATTRIBUTE` and `memberof.ldap.attribute=memberOf`.
Group joins in `LDAP_ONLY` mode still write `member` on the group, which in turn updates the user's `memberOf`.
A `customUserSearchFilter` such as `(memberOf=cn=developers,ou=groups,dc=example,dc=test)` restricts federation to the members of a group.
[`LdapSchemaMemberOfIT`](../quarkus-integration-tests/src/test/java/io/github/wadahiro/keycloak/testframework/quarkus/LdapSchemaMemberOfIT.java) combines a writable custom-attribute mapper, memberOf-based groups, and that filter.

Both options are off by default; without them the server has the standard schema only and no `memberOf` attribute type.

## Coordinated state reset

`LdapTestServer` participates in `Snapshot` automatically.
The baseline includes LDAP directory data and Keycloak's database state after `@TestSetup` completes.
If you inject `@InjectSnapshot Snapshot`, calling `snapshot.capture()` manually replaces both the directory and database baselines.
After each test method, the LDAP snapshot is restored before Keycloak's H2 restore clears Keycloak caches.
This keeps external LDAP entries, the LDAP provider and mapper configuration, and imported local users on the same baseline.

Use CLASS lifecycle for the LDAP server and other fixtures when using snapshot reset.
The default LDAP server lifecycle is CLASS.
Do not run tests in parallel against the shared Keycloak server.

LDAP server configuration, schema, and listener settings are fixed for the life of the injected server; snapshots capture directory data, not server configuration.
Test classes that request different `@InjectLdapTestServer` settings get separate servers.
The helper currently uses plain LDAP/simple bind; apart from the optional `memberOf` emulation, it does not emulate Active Directory-specific behavior, TLS, StartTLS, or SASL.

See [snapshot lifecycle](snapshot.md) for snapshot setup and reset boundaries.
