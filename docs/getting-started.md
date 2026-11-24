# Getting started

Choose a supported Keycloak release to match the deployment you want to validate, then use its matching runtime artifact. The server starts in a child JVM from Maven-resolved libraries, so there is no ZIP installation or container to manage.
Keep integration tests in a separate Maven module so their dependencies do not conflict with application code or existing unit tests.
This example targets Keycloak 24.0.5 while using the latest release of this extensions project.
The extensions BOM also imports the Keycloak Test Framework BOM, so the extension modules and Framework APIs stay aligned.

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

Set the extension version once and import this project's BOM:

```xml
<properties>
    <keycloak-test-framework-extensions.version>[0,)</keycloak-test-framework-extensions.version>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.wadahiro.keycloak.testframework</groupId>
            <artifactId>keycloak-test-framework-extensions-bom</artifactId>
            <version>${keycloak-test-framework-extensions.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

`[0,)` asks Maven to resolve the latest published release; the repository disables snapshots.
Use `mvn -U` to refresh cached repository metadata when checking for a newer release.
Version ranges make builds change as new releases appear; replace `[0,)` with a specific release version when you need repeatable builds.
Maven's [reproducible-build guidance](https://maven.apache.org/guides/mini/guide-reproducible-builds.html) explains why fixed versions are preferable for that use case.
The imported BOM manages this project's extension and runtime artifacts and imports the compatible Keycloak Test Framework BOM.

Add exactly one matching adapter as a test dependency and configure its runtime under the runtime Maven plugin's dependencies. This example uses `keycloak-test-framework-quarkus` with `keycloak-test-runtime-quarkus24`; for another supported Keycloak release, choose its matching Quarkus runtime. For Keycloak 15.0.2, use `keycloak-test-framework-undertow` with `keycloak-test-runtime-undertow15`.

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-quarkus</artifactId>
    <scope>test</scope>
</dependency>
```

For LDAP User Federation and LDAP mapper tests, add the in-memory directory helper:

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-ldap</artifactId>
    <scope>test</scope>
</dependency>
```

The LDAP helper uses UnboundID's in-memory LDAP server and restores its directory snapshot together with the database baseline.
See [LDAP federation testing](ldap-testing.md) for setup and scope.

The extensions BOM also manages Keycloak Test Framework modules. For OIDC browser-flow tests, add its OAuth module:

```xml
<dependency>
    <groupId>org.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-oauth</artifactId>
    <scope>test</scope>
</dependency>
```

The Framework's OAuth module provides `@InjectOAuthClient`. To use the shared browser journey API for OIDC, add this repository's auth helper as a test dependency:

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-auth-oidc</artifactId>
    <scope>test</scope>
</dependency>
```

For SAML browser-flow tests, add this repository's SAML helper as a test dependency. It includes the shared auth API transitively:

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-saml</artifactId>
    <scope>test</scope>
</dependency>
```

For tests where Keycloak brokers authentication to an external OIDC or SAML provider, add the broker test IdP fixtures:

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-broker</artifactId>
    <scope>test</scope>
</dependency>
```

See [OIDC and SAML login examples](client-login-examples.md) and the [broker authentication test guide](broker-testing-design.md) for browser-flow examples and IdP fixtures. Keep integration tests in a separate Maven module from applications or SPI providers compiled against older Keycloak APIs. Test-scoped Framework dependencies do not affect the main source compile classpath, but they are present on the integration test compile and runtime classpaths and can conflict with old Keycloak types if test sources compile the SPI implementation in the same module.

After importing the BOM and defining the version property above, add the runtime Maven plugin to the build's `<plugins>` section.
The plugin and its runtime artifact are not managed as project dependencies by the BOM, so use the same version property for both.

```xml
<plugin>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-runtime-maven-plugin</artifactId>
    <version>${keycloak-test-framework-extensions.version}</version>
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
            <version>${keycloak-test-framework-extensions.version}</version>
        </dependency>
    </dependencies>
</plugin>
```

Choose the runtime artifact that matches your target Keycloak release: this example uses `keycloak-test-runtime-quarkus24` for Keycloak 24.0.5.
The plugin resolves Keycloak server dependencies in its isolated Maven class realm, keeping them out of the test and IDE classpaths. More details, including direct IDE launches, are in [runtime plugin setup](runtime-plugin.md).

## Run a test

The Keycloak Test Framework reads `keycloak-test.properties` from the test classpath as an optional project-level defaults file. This is a Framework convention, not a file introduced by this extension. You can use environment variables or system properties instead; environment variables are convenient for CI matrices.

No `keycloak-test.properties` is needed to select the server or admin client; the adapter supplies those defaults.
You may create `src/test/resources/keycloak-test.properties` for optional Framework settings, such as using a headless browser in CI:

```properties
kc.test.browser=chrome-headless
```

For Keycloak 15.0.2, add the Undertow adapter and matching runtime artifact; set `KEYCLOAK_JAVA_HOME` to JDK 11, or set `kc.test.server.extension.undertow.java.home`.
For Keycloak 24.0.5, point `KEYCLOAK_JAVA_HOME` to JDK 17, then run the integration-test module with `mvn verify`.
Use the JDK listed in the table for other Keycloak versions, and make sure the runtime artifact configured in Maven matches the selected server version.
Each adapter contains default Framework supplier selections, so no `kc.test.server` or `kc.test.keycloak` setting is needed in normal use.
Override those standard Framework settings only when you intentionally want another supplier.
The runtime artifact configured in Maven selects the Keycloak server version, so no separate version property is needed.

For test fixture setup and state snapshot behavior, see [snapshot lifecycle](snapshot.md).
