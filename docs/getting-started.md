# Getting started

Use a separate integration-test module so its JDK and dependencies do not change the existing unit-test setup. The example below uses Keycloak 26.8.2; choose the runtime adapter that matches your Keycloak version.

## Supported versions

| Keycloak | Server JDK | Runtime adapter |
| --- | --- | --- |
| 15.0.2 | 11 | `keycloak-test-runtime-undertow15` |
| 20.0.5, 21.1.2, 22.0.5, 23.0.7, 24.0.5 | 17 | `keycloak-test-runtime-undertow<major>` |
| 25.0.6 | 21 | `keycloak-test-runtime-undertow25` |
| 26.8.2 | 25 | `keycloak-test-runtime-undertow26` |

The Maven integration-test runner and Keycloak Test Framework use JDK 25. The server starts in a child JVM selected by `KEYCLOAK_JAVA_HOME`, so the server can use its supported JDK independently.

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

Add the extension as a test dependency and configure the runtime adapter under the runtime Maven plugin's dependencies. Replace `26` and the Keycloak version with the adapter and version you selected above.

```xml
<dependency>
    <groupId>io.github.wadahiro</groupId>
    <artifactId>keycloak-test-framework-undertow</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

For LDAP User Federation and LDAP mapper tests, add the in-memory directory helper:

```xml
<dependency>
    <groupId>io.github.wadahiro</groupId>
    <artifactId>keycloak-test-framework-ldap</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

The LDAP helper uses UnboundID's in-memory LDAP server and restores its directory snapshot together with the H2 baseline.
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
    <groupId>io.github.wadahiro</groupId>
    <artifactId>keycloak-test-framework-saml</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

For tests where Keycloak brokers authentication to an external OIDC or SAML provider, add the broker test IdP fixtures:

```xml
<dependency>
    <groupId>io.github.wadahiro</groupId>
    <artifactId>keycloak-test-framework-broker</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

See [OIDC and SAML login examples](client-login-examples.md) and [broker authentication test design](broker-testing-design.md) for browser-flow examples and IdP fixtures. Keep integration tests in a separate Maven module from applications or SPI providers compiled against older Keycloak APIs. Test-scoped Framework dependencies do not affect the main source compile classpath, but they are present on the integration test compile and runtime classpaths and can conflict with old Keycloak types if test sources compile the SPI implementation in the same module.

In the build's `<plugins>` section, add:

```xml
<plugin>
    <groupId>io.github.wadahiro</groupId>
    <artifactId>keycloak-test-runtime-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution>
            <id>prepare-keycloak-runtime</id>
            <phase>process-test-classes</phase>
            <goals><goal>prepare</goal></goals>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>io.github.wadahiro</groupId>
            <artifactId>keycloak-test-runtime-undertow26</artifactId>
            <version>0.1.0</version>
        </dependency>
    </dependencies>
</plugin>
```

The plugin resolves Keycloak server dependencies in its isolated Maven class realm, keeping them out of the test and IDE classpaths. More details, including direct IDE launches, are in [runtime plugin setup](runtime-plugin.md).

## Run a test

Set the child server JDK and select the matching runtime in `src/test/resources/keycloak-test.properties`:

```properties
kc.test.server=undertow-process
kc.test.keycloak=undertow-admin
kc.test.undertow.version=26
```

For Keycloak 26, point `KEYCLOAK_JAVA_HOME` to JDK 25, then run the integration-test module with `mvn verify`. Use the JDK listed in the table for other Keycloak versions. The runtime adapter configured in Maven must match `kc.test.undertow.version`.

For test fixture setup and database snapshot behavior, see [H2 snapshot lifecycle](h2-snapshot.md).
