# Keycloak Test Framework Extensions

Test Keycloak configuration and custom providers through real browser login flows using the [Keycloak Test Framework](https://github.com/keycloak/keycloak/tree/main/test-framework). The extensions add isolated server runtimes, fast H2 reset, SAML client support, OIDC/SAML broker IdP fixtures, and in-memory LDAP federation support.

The server runtime is resolved from Maven; no Keycloak installation or container is needed. The extension supports Keycloak 15.0.2 and selected releases from 20 through 26. See [supported versions and JDKs](docs/getting-started.md#supported-versions).

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

Then follow the [Getting Started guide](docs/getting-started.md) to add the extension and matching runtime adapter to your Maven configuration.

## Use

Use the Framework's standard test annotations, with `@InjectH2Snapshot` when a test needs fast database-state restoration:

```java
@KeycloakIntegrationTest
public class LoginIT {
    @InjectRealm(lifecycle = LifeCycle.CLASS)
    ManagedRealm realm;

    @InjectH2Snapshot
    H2Snapshot snapshot;
}
```

See [Getting Started](docs/getting-started.md) to configure the Maven repository and dependencies. The guides cover [OIDC and SAML browser login](docs/client-login-examples.md), [identity brokering](docs/broker-testing-design.md), [LDAP federation](docs/ldap-testing.md), [H2 reset](docs/h2-snapshot.md), and [IDE test execution](docs/ide-testing.md).

Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
This independent community extension was developed with assistance from OpenAI Codex.
