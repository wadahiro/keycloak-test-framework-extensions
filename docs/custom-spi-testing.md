# Testing custom Keycloak providers

Declare custom providers through the official Keycloak Test Framework server configuration.
The extension consumes `dependency(...)` and `dependencyCurrentProject()` for both the Quarkus and Undertow 15 runtimes.
The selected runtime adapter makes the provider visible to Keycloak: Quarkus registers it with its bootstrap and provider directory, while Undertow 15 deploys it in the isolated server JVM the way WildFly deploys a provider JAR (see [class loading on Undertow 15](#class-loading-and-built-in-providers-on-undertow-15)).

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

The extension packages the module's compiled main classes as a provider for Quarkus; Undertow 15 deploys the classes directory like a provider JAR.
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
Quarkus places it in the isolated server's `providers` directory before its bootstrap build; Undertow 15 deploys the artifact in the child JVM before Keycloak boots.
Provider JAR dependencies should follow Keycloak's provider packaging guidance; the extension does not shade or rewrite provider artifacts.

## Class loading and built-in providers on Undertow 15

Undertow 15 does not put provider dependencies on the child JVM's `-cp`.
The child JVM loads them with a dedicated class loader whose parent is the runtime class loader, and registers each dependency as a Keycloak deployment before Keycloak boots, like a provider JAR in WildFly's `deployments` directory at startup:

1. Classes are found parent first: Keycloak's own classes and libraries come from the runtime, and the dependency's classes from the dependency, as Keycloak modules are searched before a WildFly deployment's own classes.
   All provider dependencies share the class loader, so a provider can use classes of another declared dependency.
2. Keycloak provider registrations (`META-INF/services/org.keycloak.*`), `META-INF/keycloak-themes.json`, `theme/` and `theme-resources/` are read from the dependency itself, never from Keycloak's JARs.
3. Keycloak loads its built-in providers first and the deployments after them.
   A dependency's factory with the same SPI and provider id as a built-in factory replaces it, for example a `LoginFormsProviderFactory` with id `freemarker`, an `IdentityProviderFactory` with id `saml`, or an LDAP mapper factory with id `user-attribute-ldap-mapper`.

Each replacement is logged in the child's `server.log` (under `kc.test.server.extension.undertow.work`), with one deployment line per dependency:

```text
KCTF_PROVIDER DEPLOY name=my-keycloak-provider-1.0.jar themes=true themeResources=false path=...
KCTF_PROVIDER OVERRIDE spi=eventsListener id=jboss-logging provider=com.example.MyEventListenerProviderFactory replaces=org.keycloak.events.log.JBossLoggingEventListenerProviderFactory deployment=my-keycloak-provider-1.0.jar
```

Themes in a dependency (`META-INF/keycloak-themes.json` and `theme/<name>/<type>/`) and theme resources (`theme-resources/templates`, `theme-resources/messages`, `theme-resources/resources`) are deployed with it, as on WildFly: the themes are selectable by name, and theme resources extend every theme.
Each dependency is one deployment named after its file name, so themes and theme resources from several dependencies do not hide each other.

Keycloak 15 reads SPI definitions only through its built-in provider manager, so the adapter adds the definitions a dependency registers in `META-INF/services/org.keycloak.provider.Spi` to it; a dependency can define its own SPI and its providers.
If two dependencies register the same SPI and provider id, the later dependency in the server configuration wins.

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
Apart from `proxy-headers=xforwarded` (see [testing behind a reverse proxy](front-proxy-testing.md)), other server options and feature flags are rejected on Undertow 15.

For providers that read a request header set by a reverse proxy, or the client address from `X-Forwarded-For`, see [testing behind a reverse proxy](front-proxy-testing.md).

The per-run Keycloak home is disposable, so provider changes are picked up by starting a new test server.
The extension does not hot-deploy provider changes into an already-running GLOBAL server.
