# Testing custom Keycloak providers

The Quarkus runtime starts from Maven-resolved Keycloak artifacts in a separate JVM.
Custom providers must be declared through the Keycloak Test Framework server configuration; the Quarkus bootstrap does not automatically treat every test dependency as a provider.

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

The extension packages the module's compiled main classes into a provider JAR in the isolated Keycloak home before bootstrap.
Keep legacy SPI source and Framework integration tests in separate Maven modules when they require different Keycloak API versions.

## Provider published as a Maven artifact

Declare a resolvable provider artifact in the server configuration:

```java
@Override
public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
    return config.dependency("com.example", "my-keycloak-provider");
}
```

The extension resolves the artifact and places it in the isolated server's `providers` directory before `start-dev` performs its build.
Provider JAR dependencies should follow Keycloak's provider packaging guidance; the extension does not shade or rewrite provider artifacts.

The per-run Keycloak home is disposable, so provider changes are picked up by starting a new test server.
The extension does not hot-deploy provider changes into an already-running GLOBAL server.
