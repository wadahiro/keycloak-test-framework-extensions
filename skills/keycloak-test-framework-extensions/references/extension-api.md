# Extension API and consumer setup

Install locally until the maintainer publishes artifacts:

```sh
mvn -pl runtime-classpath-maven-plugin,extension-undertow,extension-saml,extension-broker,extension-ldap,runtime-undertow15 -am install -DskipTests
```

Add `io.github.wadahiro.keycloak.testframework:keycloak-test-framework-undertow:0.1.0-SNAPSHOT` to a separate integration-test module with test scope.
Configure the matching `keycloak-test-runtime-undertow<major>` artifact as a dependency of `keycloak-test-runtime-maven-plugin`, including for 15.0.2. Do not add this artifact as a project dependency; keeping it in the Maven plugin realm prevents Keycloak classes from leaking into the test JVM.
Import `org.keycloak.testframework:keycloak-test-framework-bom:26.8.2` in the consumer dependency management.
The extension uses standard service discovery without Framework source changes.
Its main dependency on Framework core must explicitly use compile scope because the BOM manages Framework artifacts with test scope.
Consumers still depend on the extension with test scope.

In `src/test/resources/keycloak-test.properties`:

```properties
kc.test.server=undertow-process
kc.test.keycloak=undertow-admin
```

Set `KEYCLOAK_JAVA_HOME` or `kc.test.undertow.java.home` to the server JDK required for the selected Keycloak version.
Keycloak 15 uses Java 11; 20–24 use Java 17; 25 uses Java 21; 26 uses Java 25.
Configure one runtime artifact per integration-test module and set `kc.test.undertow.version` to that exact version.
The optional `kc.test.undertow.work` retains child process files and logs; Maven stores server dependency JARs in the local repository.
Disposable administrator credentials default to `admin` / `admin` and can be configured through `kc.test.undertow.admin.username` and `.admin.password`.
Listeners bind to loopback.
The runtime Maven plugin generates the child JVM classpath descriptor in `target/test-classes/META-INF/keycloak-test-runtime.properties`; bind `prepare` to `process-test-classes` or run it before direct IDE JUnit launches.

Use the [runtime plugin guide](../../../docs/runtime-plugin.md) for the runtime adapter setup and the [H2 snapshot guide](h2-snapshot.md) for annotations, fixture setup and reset boundaries.

For LDAP User Federation tests, add `io.github.wadahiro.keycloak.testframework:keycloak-test-framework-ldap` with test scope.
It starts an UnboundID in-memory LDAP server in the test JVM and coordinates directory snapshot capture and restore with `H2Snapshot`.
Read [the LDAP testing guide](../../../docs/ldap-testing.md) for LDAP component configuration and supported limitations.
