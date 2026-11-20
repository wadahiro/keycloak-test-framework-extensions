# H2 snapshot reset with the Undertow extension

`keycloak-test-framework-undertow` starts the selected supported Keycloak runtime in a separate JVM and restores a shared H2 in-memory baseline after each test method.
The modern Test Framework remains unmodified and runs on JDK 25.
Each supported Keycloak version has a small runtime adapter artifact. The extension and adapter JARs do not bundle Keycloak server libraries; the runtime Maven plugin resolves those libraries outside the JUnit classpath.
The runtime uses Undertow and local JPA transactions.

## Consumer setup

Use a separate integration-test module, preserving existing Java 11 unit tests.
Import the Framework BOM in that consumer module to align its transitive dependencies:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.keycloak.testframework</groupId>
            <artifactId>keycloak-test-framework-bom</artifactId>
            <version>26.8.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>io.github.wadahiro</groupId>
        <artifactId>keycloak-test-framework-undertow</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Select the suppliers with Maven Failsafe `systemPropertyVariables` or equivalent system properties:

```xml
<kc.test.server>undertow-process</kc.test.server>
<kc.test.keycloak>undertow-admin</kc.test.keycloak>
```

Set `KEYCLOAK_JAVA_HOME` to the JDK required by the selected version: 11 for 15.0.2, 17 for 20–24, 21 for 25, or 25 for 26.
Configure the selected runtime adapter as a dependency of `keycloak-test-runtime-maven-plugin`; see [runtime setup](runtime-plugin.md). Use one runtime per integration-test module, and keep `kc.test.undertow.version` aligned with it when that property is set.
`kc.test.undertow.work` optionally selects the directory retaining child process logs and run files. Maven keeps the resolved server libraries in the local repository.
`kc.test.undertow.admin.username` and `.admin.password` default to `admin`.
The server has GLOBAL lifecycle within one Framework registry/test JVM.
Multiple Maven forks have independent servers and databases.
See the [integration module](../undertow-integration-tests/pom.xml) for the complete Failsafe logging configuration.

```java
@KeycloakIntegrationTest
public class ExampleIT {
    @InjectRealm(lifecycle = LifeCycle.CLASS, config = Fixture.class)
    ManagedRealm realm;

    @InjectH2Snapshot
    H2Snapshot snapshot;

    public static class Fixture implements RealmConfig {
        @Override
        public RealmBuilder configure(RealmBuilder builder) {
            return builder.users(UserBuilder.create("alice").enabled(true).password("password"));
        }
    }

    @Test
    void changeSomething() {
        var user = realm.admin().users().search("alice").getFirst();
        user.setFirstName("changed");
        realm.admin().users().get(user.getId()).update(user);
        // The original row, credentials and caches return after this method.
    }
}
```

The custom annotation and handle are in `io.github.wadahiro.keycloak.undertow`.
Other types are the standard Framework/JUnit types; no extra JUnit extension registration is needed.

## Lifecycle and explicit operations

The snapshot supplier uses the Framework's `Supplier.onBeforeEach` and `ManagedTestResource.runCleanup` hooks.
On the first method it captures all declarative fixtures after resources are created, before user setup methods.
After the test and user `@AfterEach`, normal Framework cleanup runs, then the snapshot restores the database and caches.
The next user `@BeforeEach` therefore sees the original baseline.
Class cleanup releases the baseline before the Framework removes that class's realms and clients.
A new class can capture its own fixture on the same running server.

To include imperative fixture preparation, call `snapshot.capture()` at the end of a public Framework `@TestSetup` method on a public test class:

```java
@TestSetup
public void prepareOnce() {
    // Create/update reusable fixtures through the admin API.
    snapshot.capture();
}
```

`capture()` replaces the current baseline; changes made before that call become intentional baseline state.
`restore()` performs an immediate reset within a test and automatic cleanup still runs afterwards.
`getLastRestoreMillis()` reports the most recent complete restore/control round trip.
Use a single baseline per class; multiple independently selected baselines are not supported.
The public handle is owned and closed by the Framework.

## What is restored

The selected runtime captures detached JDBC rows and restores them with prepared batches into the existing H2 schema.
It truncates all PUBLIC tables with referential integrity temporarily disabled, re-inserts the baseline and restores sequence next-values, including generated identity sequences.
User/client/realm IDs stay stable; credentials, roles and other baseline table contents are restored as data.
This is a logical copy, whose cost grows with fixture size, not a native H2 copy-on-write snapshot.

The runtime clears Hibernate second-level data and Keycloak model/revision/key caches.
It also clears transient authentication-session, login-failure and action-token caches when those caches are configured.
For versions 20–25, online/offline user-session and client-session caches are restored from serialized baseline copies using Infinispan's marshaller.
Keycloak 26.8 stores user sessions in the database by default, so those rows return with the H2 restore.
Deep copies are important: live references could be mutated by the test itself.
Sessions created after capture disappear; sessions already in the baseline return, including Framework administrative sessions.
This avoids forcing an admin password login after every reset.
Ongoing browser authentication flows and transient action-token/login-failure state are not baseline fixtures.
Wall-clock time is not rewound; session/token expiry still applies.
Already issued access tokens are not generally revoked by resetting the database.

A read/write barrier covers complete synchronous HTTP requests, including transaction completion, and the configured Keycloak timer tasks.
Reset waits up to ten seconds for that work to finish and prevents new requests from entering while data/caches are inconsistent.
An uncommitted H2 transaction is rejected before truncation.
A snapshot/restore/control failure stops the child and makes that server unusable, rather than allowing later tests to use partially restored state.
Child EOF/shutdown handling also closes Keycloak when the test process exits.

## Supported boundaries

- Supported runtime versions and their server JDKs are listed in the [README](../README.md).
- Use CLASS/GLOBAL resources; METHOD-scoped injected resources are rejected before capture.
  Do not call `dirty()` on managed resources while using a baseline: recreation conflicts with stable restored IDs.
- JUnit parallel execution is rejected; asynchronous requests/jobs must finish before a method returns.
  The barrier covers the runtime's HTTP and standard timer paths, not arbitrary external asynchronous providers.
- The entire server DB is restored, including master and unrelated realms on this disposable server.
  Do not share this server with other test runners or applications.
- Admin Console and Account Console frontend assets are omitted from the packaged server runtime; exercise the server through its APIs and login pages.
- Keep the schema fixed; metadata changes such as table/column/index/foreign-key changes fail reset.
  Arbitrary DDL and non-PUBLIC tables are not supported workloads.
- External stores, files, emails, custom provider state, JVM system properties and arbitrary factory caches are not snapshotted.
  Modern `KeycloakServerConfig`, provider hot deployment, custom runtime classpaths and TLS are not implemented by this backend.

## Build and verification

```sh
mvn -pl undertow-integration-tests -am verify
```

The runtime unit tests check large CLOB/BLOB values, nulls, foreign keys, identity/sequence replay, schema-change rejection, uncommitted transactions and the request barrier.
Host guard tests reject parallel execution, unsupported backends and METHOD-scoped resources.
Integration tests verify stable IDs, restore timing relative to user setup/teardown, credentials, transient/baseline sessions, refresh tokens, whole-DB master-realm reset, cross-class cleanup/reuse, wrong-JDK rejection and child termination after a control failure.
The modern test JVM also verifies that server runtime classes are absent from its own classpath.
