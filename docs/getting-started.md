# Getting started

Choose a supported Keycloak release to match the deployment you want to validate, then use its matching runtime artifact. The server starts in a child JVM from Maven-resolved libraries, so there is no ZIP installation or container to manage.
Keep integration tests in a separate Maven module so their dependencies do not conflict with application code or existing unit tests.
This example targets Keycloak 24.0.5 while using the latest Keycloak Test Framework BOM (26.8.2); the Framework version and server version are selected independently.

## Supported versions

| Keycloak | Server JDK | Extension artifact | Runtime artifact |
| --- | --- | --- | --- |
| 15.0.2 | 11 | `keycloak-test-framework-undertow` | `keycloak-test-runtime-undertow15` |
| 20.0.5, 21.1.2, 22.0.5, 23.0.7, 24.0.5 | 17 | `keycloak-test-framework-quarkus` | `keycloak-test-runtime-quarkus<major>` |
| 25.0.6 | 21 | `keycloak-test-framework-quarkus` | `keycloak-test-runtime-quarkus25` |
| 26.8.2 | 25 | `keycloak-test-framework-quarkus` | `keycloak-test-runtime-quarkus26` |

The Undertow adapter targets Keycloak 15.0.2; the Quarkus adapter targets Keycloak 20–26. The Maven integration-test runner and Keycloak Test Framework use JDK 25. The server starts in a child JVM selected by the adapter's Java-home setting, so it can use the JDK supported by its Keycloak release.

## Configure Maven

Add the repository to the integration-test module's `pom.xml`. Releases are published to the `mvn-repo` branch; snapshots are not published there.

```xml
<repositories>
    <repository>
        <id>keycloak-test-framework-extensions</id>
        <url>https://raw.githubusercontent.com/wadahiro/keycloak-test-framework-extensions/mvn-repo</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

Add the matching adapter as a test dependency and configure its runtime under the runtime Maven plugin's dependencies. This example uses `keycloak-test-framework-quarkus` with `keycloak-test-runtime-quarkus24`; for another supported Keycloak release, choose its matching Quarkus runtime. For Keycloak 15.0.2, use `keycloak-test-framework-undertow` with `keycloak-test-runtime-undertow15`.

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-quarkus</artifactId>
    <version>0.2.0</version>
    <scope>test</scope>
</dependency>
```

For LDAP User Federation and LDAP mapper tests, add the in-memory directory helper:

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-ldap</artifactId>
    <version>0.2.0</version>
    <scope>test</scope>
</dependency>
```

The LDAP helper uses UnboundID's in-memory LDAP server and restores its directory snapshot together with the database baseline.
See [LDAP federation testing](ldap-testing.md) for setup and scope.

The extension brings in the Keycloak Test Framework core. For OIDC browser-flow tests, add its OAuth module as well:

```xml
<dependency>
    <groupId>org.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-oauth</artifactId>
    <version>26.8.2</version>
    <scope>test</scope>
</dependency>
```

The Framework's OAuth module provides `@InjectOAuthClient`. For SAML browser-flow tests, add this repository's SAML helper as a test dependency:

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-saml</artifactId>
    <version>0.2.0</version>
    <scope>test</scope>
</dependency>
```

For tests where Keycloak brokers authentication to an external OIDC or SAML provider, add the broker test IdP fixtures:

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-broker</artifactId>
    <version>0.2.0</version>
    <scope>test</scope>
</dependency>
```

See [OIDC and SAML login examples](client-login-examples.md) and the [broker authentication test guide](broker-testing-design.md) for browser-flow examples and IdP fixtures. Keep integration tests in a separate Maven module from applications or SPI providers compiled against older Keycloak APIs. Test-scoped Framework dependencies do not affect the main source compile classpath, but they are present on the integration test compile and runtime classpaths and can conflict with old Keycloak types if test sources compile the SPI implementation in the same module.

In the build's `<plugins>` section, add:

```xml
<plugin>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-runtime-maven-plugin</artifactId>
    <version>0.2.0</version>
    <executions>
        <execution>
            <id>prepare-keycloak-runtime</id>
            <phase>process-test-classes</phase>
            <goals><goal>prepare</goal></goals>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>io.github.wadahiro.keycloak.testframework</groupId>
            <artifactId>keycloak-test-runtime-quarkus24</artifactId>
            <version>0.2.0</version>
        </dependency>
    </dependencies>
</plugin>
```

Choose the runtime artifact that matches your target Keycloak release: this example uses `keycloak-test-runtime-quarkus24` for Keycloak 24.0.5.
The plugin resolves Keycloak server dependencies in its isolated Maven class realm, keeping them out of the test and IDE classpaths. More details, including direct IDE launches, are in [runtime plugin setup](runtime-plugin.md).

## Run a test

Set the child server JDK and select the server adapter's suppliers in `src/test/resources/keycloak-test.properties`:

```properties
kc.test.server=quarkus-process
kc.test.keycloak=quarkus-admin
kc.test.quarkus.version=24.0.5
```

For Keycloak 15.0.2, use `kc.test.server=undertow-process`, `kc.test.keycloak=undertow-admin`, and set `kc.test.undertow.java.home` to the JDK 11 home.
For Keycloak 24.0.5, point `KEYCLOAK_JAVA_HOME` to JDK 17, then run the integration-test module with `mvn verify`.
Use the JDK listed in the table for other Keycloak versions, and make sure the runtime artifact configured in Maven matches the selected server version.

For test fixture setup and state snapshot behavior, see [snapshot lifecycle](snapshot.md).
