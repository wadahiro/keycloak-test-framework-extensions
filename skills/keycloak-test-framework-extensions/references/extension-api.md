# Extension API and consumer setup

Install the required modules locally until artifacts are published:

```sh
mvn -pl runtime-classpath-maven-plugin,extension-quarkus,extension-snapshot,extension-saml,extension-broker,extension-ldap,runtime-quarkus/26 -am install -DskipTests
# For the Keycloak 15.0.2 adapter and integration tests:
mvn -Pundertow15 -pl undertow-integration-tests -am install -DskipTests
```

Import `io.github.wadahiro.keycloak.testframework:keycloak-test-framework-extensions-bom` in dependency management and omit versions on extension and Framework test dependencies.
Set `keycloak-test-framework-extensions.version` once; `[0,)` selects the latest published release, while a fixed version makes builds repeatable.
The extensions BOM imports the Keycloak Test Framework BOM used to build the extension.
Add `keycloak-test-framework-quarkus` to a separate integration-test module with test scope.
Configure one matching `keycloak-test-runtime-quarkus<major>` artifact as a dependency of `keycloak-test-runtime-maven-plugin`, not as a project dependency.
Use the same version property for the runtime Maven plugin and its runtime artifact, which are not version-managed by the BOM.
The runtime Maven plugin keeps server libraries outside the test and IDE classpaths.

The selected adapter automatically configures its `server` and `keycloak` suppliers.
The runtime Maven plugin's selected runtime artifact determines the exact Keycloak version, so a separate version property is not needed.
Set `KEYCLOAK_JAVA_HOME` to the server JDK, or use the extension-specific Java-home setting if the environment variable is not suitable.

For optional Framework defaults such as headless browser selection, `src/test/resources/keycloak-test.properties` may contain:

```properties
kc.test.browser=chrome-headless
```

Set `KEYCLOAK_JAVA_HOME` to the server JDK: 17 for Keycloak 20–24, 21 for 25, or 25 for 26.
The Test Framework and IDE JUnit process use JDK 25.
The child Keycloak process uses `KEYCLOAK_JAVA_HOME` independently.

## Deploy a custom SPI

Declare providers through the official Framework server configuration.
Both the Quarkus and Undertow 15 adapters consume `dependency(...)` and `dependencyCurrentProject()`; each adapter makes the provider visible through its own runtime loading mechanism.

When provider sources are in the integration-test module's main source set, use:

```java
public final class ServerConfig implements KeycloakServerConfig {
    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return config.dependencyCurrentProject();
    }
}
```

Then select it on the test class with `@KeycloakIntegrationTest(config = ServerConfig.class)`.
The Quarkus adapter packages the compiled module as a provider JAR.
The Undertow 15 adapter adds the compiled classes directory to its isolated server JVM classpath.

For a separately built SPI artifact, declare it in the server configuration with `config.dependency("groupId", "artifactId")` and make sure that artifact is resolvable from the Maven repository.
Keep SPI development and Framework integration tests in separate Maven modules when they target different Keycloak API versions.

The Quarkus adapter places registered provider JARs in its isolated server home before bootstrap.
The Undertow 15 adapter resolves provider JARs into the child JVM classpath.
Provider changes require a fresh server process.

The Undertow 15 adapter consumes provider dependencies and `spiOption(spi, provider, key, value)` from `KeycloakServerConfigBuilder`; it writes each SPI option into the Keycloak 15 JSON configuration as `<spi>.<provider>.<key>` and rejects other server options or feature flags, which its fixed Keycloak 15 bootstrap cannot apply.
Run its integration tests with `mvn -Pundertow15 verify`, the Test Framework on JDK 25, and `KEYCLOAK_JAVA_HOME` set to JDK 11.

The runtime Maven plugin generates `target/test-classes/META-INF/keycloak-test-runtime.properties`; bind `prepare` to `process-test-classes` or run it before direct IDE JUnit launches.
See the [runtime plugin guide](../../../docs/runtime-plugin.md), [snapshot guide](../../../docs/snapshot.md), and [IDE guide](../../../docs/ide-testing.md).

For LDAP federation tests, add `keycloak-test-framework-ldap` with test scope.
It starts an UnboundID in-memory LDAP server and restores its directory snapshot alongside the database baseline.
`@InjectLdapTestServer(schema = "ldap/x-example.schema")` merges custom attribute types and object classes from classpath (or `file:`) schema files into the standard schema; `.ldif` files hold subschema or OpenLDAP `cn=config` entries, `.schema` files use OpenLDAP `slapd.conf` syntax.
`memberOf = true` maintains a read-only operational `memberOf` on member entries from groups' `member` values (`memberOfMembershipAttributes` selects others, such as `uniqueMember`), for `group-ldap-mapper` with `GET_GROUPS_FROM_USER_MEMBEROF_ATTRIBUTE` and `(memberOf=...)` filters.
Writes through `ldap.directory()` bypass the emulation until the next LDAP client read, snapshot capture, or `ldap.synchronizeMemberOf()`.
Both options are off by default.
See the [LDAP testing guide](../../../docs/ldap-testing.md).

## SAML client login

For SAML browser login, add `keycloak-test-framework-saml` with test scope and inject `@InjectSamlClient SamlClient`.
Without `clientId`, it creates a temporary SAML client from `config`.
When the realm already defines the SAML client, for example from `@InjectRealm(fromJson = "/example-realm.json")`, set `clientId` to its entity ID instead, such as `@InjectSamlClient(clientId = "https://sp.example.test/saml/metadata")`.
The extension then adds its local ACS URL to that client's redirect URIs and POST assertion consumer URL through the Admin API, restores both when the value closes, and leaves signing, NameID, and mapper settings as the realm defines them.
Prefer this over recreating the client in test code when the goal is to verify the realm's real client configuration.

`startLogin()` runs SP-initiated login.
`startIdpInitiatedLogin()` opens the client's IdP-initiated SSO URL (`saml_idp_initiated_sso_url_name`) and accepts the unsolicited response; `result.relayState()` exposes the RelayState Keycloak sent.
Both return a flow whose `browser()` drives the login pages and whose `awaitResult(...)` validates the signed response.
End the user's sessions between logins in the same browser, or Keycloak's SSO session skips the login form.
The test SP does not sign AuthnRequests, so use IdP-initiated login for clients that require client signatures; encrypted assertions and logout are not supported.
See [browser login examples](../../../docs/client-login-examples.md#saml).
