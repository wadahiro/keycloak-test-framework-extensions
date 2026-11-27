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

Then follow the [Getting Started guide](docs/getting-started.md) to choose a Keycloak version and configure its matching adapter, runtime artifact, and JDK.

Further guides cover [OIDC and SAML browser login](docs/client-login-examples.md), [identity brokering](docs/broker-testing-design.md), [custom SPI testing](docs/custom-spi-testing.md), [LDAP federation](docs/ldap-testing.md), [reverse-proxy headers and client addresses](docs/front-proxy-testing.md), [state reset](docs/snapshot.md), and [IDE test execution](docs/ide-testing.md).

Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
