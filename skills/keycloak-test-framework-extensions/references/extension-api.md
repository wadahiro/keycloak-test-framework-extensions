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

The Undertow 15 adapter consumes provider dependencies from `KeycloakServerConfigBuilder` and rejects configured server options or feature flags, which its fixed Keycloak 15 bootstrap cannot apply.
Run its integration tests with `mvn -Pundertow15 verify`, the Test Framework on JDK 25, and `KEYCLOAK_JAVA_HOME` set to JDK 11.

The runtime Maven plugin generates `target/test-classes/META-INF/keycloak-test-runtime.properties`; bind `prepare` to `process-test-classes` or run it before direct IDE JUnit launches.
See the [runtime plugin guide](../../../docs/runtime-plugin.md), [snapshot guide](../../../docs/snapshot.md), and [IDE guide](../../../docs/ide-testing.md).

For LDAP federation tests, add `keycloak-test-framework-ldap` with test scope.
It starts an UnboundID in-memory LDAP server and restores its directory snapshot alongside the database baseline.
See the [LDAP testing guide](../../../docs/ldap-testing.md).
