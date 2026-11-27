# Testing custom Keycloak providers

Declare custom providers through the official Keycloak Test Framework server configuration.
The extension consumes `dependency(...)` and `dependencyCurrentProject()` for both the Quarkus and Undertow 15 runtimes.
The selected runtime adapter makes the provider visible to Keycloak: Quarkus registers it with its bootstrap and provider directory, while Undertow 15 adds it to the isolated server JVM classpath.

## Provider in the integration-test module

Place provider implementation classes and their `META-INF/services` registrations in the module's main source set.
Select the current project as a provider dependency:

```java
public final class TestServerConfig implements KeycloakServerConfig {
    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return config.dependencyCurrentProject();
    }
}
```

Use this configuration on the integration test:

```java
@KeycloakIntegrationTest(config = TestServerConfig.class)
class CustomProviderIT {
    // Framework-managed tests
}
```

The extension packages the module's compiled main classes as a provider for Quarkus; Undertow 15 places the classes directory on the isolated runtime classpath.
Keep legacy SPI source and Framework integration tests in separate Maven modules when they require different Keycloak API versions.

## Provider published as a Maven artifact

Declare a resolvable provider artifact in the server configuration:

```java
@Override
public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
    return config.dependency("com.example", "my-keycloak-provider");
}
```

The extension resolves the artifact and makes it available to the selected server runtime before startup.
Quarkus places it in the isolated server's `providers` directory before its bootstrap build; Undertow 15 adds the artifact to the child JVM classpath.
Provider JAR dependencies should follow Keycloak's provider packaging guidance; the extension does not shade or rewrite provider artifacts.

## Provider configuration

Pass provider settings with the Framework's `spiOption(spi, provider, key, value)`:

```java
@Override
public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
    return config.dependency("com.example", "my-keycloak-provider")
            .spiOption("realm-restapi-extension", "my-provider", "endpoint", "http://localhost:8089");
}
```

The provider factory reads the value from the `Config.Scope` given to `init`.
Quarkus receives it as the `spi-<spi>--<provider>--<key>` server option.
Undertow 15 writes it into the isolated server's JSON configuration under `<spi>.<provider>.<key>`, the place a `standalone.xml` `<spi>` element configures on a WildFly server; the names are used as written.
Values are passed as text, which `Config.Scope` parses for `getInt`, `getLong` and `getBoolean`.
Other server options and feature flags are rejected on Undertow 15.

The per-run Keycloak home is disposable, so provider changes are picked up by starting a new test server.
The extension does not hot-deploy provider changes into an already-running GLOBAL server.
