# Keycloak Test Framework Extensions

Run integration tests against supported Keycloak releases, including older versions used by existing deployments, with the [Keycloak Test Framework](https://github.com/keycloak/keycloak/tree/main/test-framework). This lets teams verify realm configuration, browser login flows, and custom providers against the Keycloak version they actually operate.

The selected server runtime is resolved from Maven and starts in a child JVM; no Keycloak ZIP installation or container is needed. The extensions support Keycloak 15.0.2 through the Undertow adapter and selected releases from 20 through 26 through the Quarkus adapter. See [supported versions and JDKs](docs/getting-started.md#supported-versions).

## Getting started

Add the GitHub-hosted Maven repository to your integration-test module. Releases are published on the `mvn-repo` branch; they are not available from Maven Central.

```xml
<repositories>
    <repository>
        <id>keycloak-test-framework-extensions</id>
        <url>https://raw.githubusercontent.com/wadahiro/keycloak-test-framework-extensions/mvn-repo</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

Then follow the [Getting Started guide](docs/getting-started.md) to add the extension and matching runtime artifact to your Maven configuration.

## Run against a Keycloak version

Choose a supported Keycloak release and matching runtime artifact. For example, to test against Keycloak 24.0.5, configure `keycloak-test.properties` and the Maven runtime plugin as shown in the [Getting Started guide](docs/getting-started.md).
Set `kc.test.quarkus.version` to the selected release and use its matching `keycloak-test-runtime-quarkus<major>` artifact; for Keycloak 15.0.2, select the Undertow adapter and runtime instead.

```properties
kc.test.server=quarkus-process
kc.test.keycloak=quarkus-admin
kc.test.quarkus.version=24.0.5
```

Tests use the Framework's regular integration-test API:

```java
@KeycloakIntegrationTest
public class LoginIT {
    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

}
```

Use `@ResetKeycloakState` only when you want automatic database and fixture restoration between test methods. Snapshot reset is optional; the server version is selected in the same way with or without it.

See [Getting Started](docs/getting-started.md) to configure the Maven repository and dependencies. The guides cover [OIDC and SAML browser login](docs/client-login-examples.md), [identity brokering](docs/broker-testing-design.md), [custom SPI testing](docs/custom-spi-testing.md), [LDAP federation](docs/ldap-testing.md), [state reset](docs/snapshot.md), and [IDE test execution](docs/ide-testing.md).

Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
This independent community extension was developed with assistance from OpenAI Codex.
