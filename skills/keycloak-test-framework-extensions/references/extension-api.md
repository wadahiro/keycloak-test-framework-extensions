# Extension API and consumer setup

Install the required modules locally until artifacts are published:

```sh
mvn -pl runtime-classpath-maven-plugin,extension-quarkus,extension-snapshot,extension-saml,extension-broker,extension-ldap,runtime-quarkus/26 -am install -DskipTests
```

Add `io.github.wadahiro.keycloak.testframework:keycloak-test-framework-quarkus:0.1.0-SNAPSHOT` to a separate integration-test module with test scope.
Configure one matching `keycloak-test-runtime-quarkus<major>` artifact as a dependency of `keycloak-test-runtime-maven-plugin`, not as a project dependency.
The runtime Maven plugin keeps server libraries outside the test and IDE classpaths.

Import `org.keycloak.testframework:keycloak-test-framework-bom:26.8.2` in dependency management.
Use the matching Framework BOM for the selected server version.

In `src/test/resources/keycloak-test.properties`:

```properties
kc.test.server=quarkus-process
kc.test.keycloak=quarkus-admin
kc.test.quarkus.version=26.8.2
```

Set `KEYCLOAK_JAVA_HOME` to the server JDK: 17 for Keycloak 20–24, 21 for 25, or 25 for 26.
The Test Framework and IDE JUnit process use JDK 25.
The child Keycloak process uses `KEYCLOAK_JAVA_HOME` independently.

## Deploy a custom SPI

The Quarkus bootstrap does not discover an arbitrary SPI project from its POM automatically.
Declare the provider through the standard Framework server configuration so the extension can package it into the isolated Keycloak home before startup.

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
The Framework's `dependencyCurrentProject()` registers the compiled module as a provider JAR.

For a separately built SPI artifact, declare it in the server configuration with `config.dependency("groupId", "artifactId")` and make sure that artifact is resolvable from the Maven repository.
Keep SPI development and Framework integration tests in separate Maven modules when they target different Keycloak API versions.

Keycloak's `start-dev` startup builds the isolated server home with the registered provider JARs.
Provider changes require a fresh server process; this extension uses a per-run home directory.

The runtime Maven plugin generates `target/test-classes/META-INF/keycloak-test-runtime.properties`; bind `prepare` to `process-test-classes` or run it before direct IDE JUnit launches.
See the [runtime plugin guide](../../../docs/runtime-plugin.md), [H2 snapshot guide](h2-snapshot.md), and [IDE guide](ide-testing.md).

For LDAP federation tests, add `keycloak-test-framework-ldap` with test scope.
It starts an UnboundID in-memory LDAP server and restores its directory snapshot alongside H2.
See the [LDAP testing guide](../../../docs/ldap-testing.md).
