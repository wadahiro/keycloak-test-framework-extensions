# Testing custom Keycloak providers

Declare custom providers through the official Keycloak Test Framework server configuration.
The extension consumes `dependency(...)` and `dependencyCurrentProject()` for both the Quarkus and Undertow 15 runtimes, and adds [provider files on disk](#provider-files-on-disk) for archives built outside the test's Maven dependencies.
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

## Provider files on disk

`dependency(groupId, artifactId)` resolves one JAR per artifact, without its transitive dependencies.
When a test should deploy the exact archives that production ships, such as a provider JAR built by another module or an EAR for a WildFly-based Keycloak 15, add them as files with `ProviderFiles` (artifact `keycloak-test-framework-provider-files`, a dependency of both adapters):

```java
import io.github.wadahiro.keycloak.testframework.providers.ProviderFiles;

@Override
public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
    return ProviderFiles.add(config, "../example-ear/target/example-ear-*.ear")
            .spiOption("realm-restapi-extension", "my-provider", "endpoint", "http://localhost:8089");
}
```

`ProviderFiles.add(config, String...)` and `ProviderFiles.add(config, Path...)` return the builder, so they chain with the Framework's methods.
A relative path is resolved against the working directory, which Maven Surefire and Failsafe set to the module's base directory.
The last path segment may be a glob (`*`, `?`, `[...]`, `{...}`) because built archive names contain versions; a glob must match exactly one file.
A second match is usually a stale build of another version left in `target/`, and deploying both would mix their classes, so it fails; list several files explicitly instead.
Globs in a directory segment are rejected.
A path that does not exist, a glob that matches no file or several, and a file that is not a `.jar`, an `.ear` or a directory fail when the configuration is built, and again at server start if a file disappeared in between.

When the provider file comes from a sibling module of the same reactor, declare that module as a `test` dependency of the integration-test module (`<type>ear</type>` for an EAR) so Maven builds it first; exclude it from the test class path with Failsafe's `classpathDependencyExcludes` if it should not be there.

Each runtime accepts these inputs:

| Input | Undertow 15 (Keycloak 15, WildFly-based) | Quarkus (Keycloak 20–26) |
|---|---|---|
| Provider JAR file | One deployment, like `dependency(...)` | Copied into the server's `providers/` directory |
| Directory (exploded JAR, such as `target/classes`) | One deployment | Packaged as a JAR into `providers/` |
| EAR file, or a directory whose name ends in `.ear` | One deployment of its modules and libraries | Rejected: `EAR provider archives are supported only by the Keycloak 15 (WildFly-based) runtime; pass the JAR files instead` |

The split follows the servers: a WildFly-based Keycloak deploys providers as WildFly deployments, including EARs, while a Quarkus distribution has no EAR deployment and takes provider JARs, including a provider's library JARs, from its `providers` directory.
On Quarkus, pass the provider JAR and each library JAR it needs.

### Ordering

The Framework keeps `dependency(...)` artifacts in an unordered set, so their order is not the declaration order.
Provider files are deployed after every `dependency(...)` artifact, in the order they were added, wherever `ProviderFiles.add` appears in the builder chain.
On Undertow 15 a later deployment replaces an earlier one's factory with the same SPI and provider id, so a provider file wins over a Maven dependency, and a later provider file over an earlier one.
When two providers must override each other in a defined order, add both as provider files.
Classes follow class-loader order instead: all provider deployments share one class loader, which searches them in deployment order, so when two deployments contain a class with the same name, the earlier deployment's class is used by both.
On Quarkus every JAR in `providers/` joins one class path and Keycloak's build chooses between factories with the same id; do not rely on an order there.

### EAR archives on Undertow 15

An EAR is expanded under the server's run directory (`provider-files/<n>-<name>.ear/`); a directory whose name ends in `.ear` is used in place.
Its members are those WildFly deploys from an EAR:

1. The JAR modules: the `<ejb>` and `<java>` modules of `META-INF/application.xml`, in descriptor order, or every top-level `.jar` in file-name order when there is no descriptor.
   `<web>` and `<connector>` modules, and top-level `.war` and `.rar` files without a descriptor, are rejected, since a provider test cannot deploy them.
2. The JARs directly in the library directory: `<library-directory>` of the descriptor, `lib` by default, none when the element is empty.

The EAR becomes one deployment named after the archive file, logged as `KCTF_PROVIDER DEPLOY name=example-ear-1.0.ear ... path=<module JAR>:<library JAR>...`.
Its Keycloak registrations, themes and theme resources are read from all of its members, and its classes join the provider class loader shared with the other provider dependencies (see below).

This differs from WildFly in three ways:

- WildFly registers one provider deployment per JAR module, named after the module, and each sees the EAR's libraries; the adapter registers one deployment for the whole EAR. With a single JAR module the result is the same; with several, a factory id defined twice inside the same EAR is resolved within that one deployment rather than between WildFly subdeployments; avoid relying on it.
- WildFly isolates an EAR's classes from other deployments; the adapter shares one class loader across all provider dependencies and files, so an EAR class can also be seen by another provider dependency, and the first member on the class loader wins if two deployments contain the same class.
- `Class-Path` manifest entries, `jboss-deployment-structure.xml` and other WildFly deployment descriptors are not applied.

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
If two deployments register the same SPI and provider id, the later deployment wins; see [ordering](#ordering) for which deployment is later.

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
Apart from `proxy-headers=xforwarded` (see [testing behind a reverse proxy](front-proxy-testing.md)) and provider files, other server options and feature flags are rejected on Undertow 15.

For providers that read a request header set by a reverse proxy, or the client address from `X-Forwarded-For`, see [testing behind a reverse proxy](front-proxy-testing.md).

The per-run Keycloak home is disposable, so provider changes are picked up by starting a new test server.
The extension does not hot-deploy provider changes into an already-running GLOBAL server.
