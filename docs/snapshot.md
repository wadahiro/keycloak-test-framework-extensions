# Snapshot reset

`keycloak-test-framework-snapshot` provides the class-level `@ResetKeycloakState` annotation for automatic reset and the optional `@InjectSnapshot Snapshot` handle for explicit reset operations.
Use `keycloak-test-framework-undertow` for Keycloak 15.0.2 or `keycloak-test-framework-quarkus` for Keycloak 20–26; each adapter supplies the same API over its supported startup path.
The current database implementation uses H2, but test code and `SnapshotParticipant` implementations do not depend on that database choice.
Each supported Keycloak version has a small runtime artifact. The extension and runtime JARs do not bundle Keycloak server libraries; the runtime Maven plugin resolves those libraries outside the JUnit classpath.

## Consumer setup

Use a separate integration-test module, preserving existing Java 11 unit tests.
Import the extensions BOM in that consumer module; it also imports the compatible Framework BOM and manages extension artifact versions:

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
<dependencies>
    <dependency>
        <groupId>io.github.wadahiro.keycloak.testframework</groupId>
        <artifactId>keycloak-test-framework-quarkus</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

The example above selects the Quarkus adapter. For Keycloak 15.0.2, use the Undertow extension and its runtime artifact in the separate integration-test module.
`[0,)` selects the latest published release; use `mvn -U` to refresh repository metadata, or set a fixed version for repeatable builds.
Each adapter selects its `server` and `keycloak` suppliers by default; set those properties only to override that choice.

Set the selected server JDK: Java 11 for 15.0.2, Java 17 for 20–24, Java 21 for 25, or Java 25 for 26.
Configure the matching runtime adapter as a dependency of `keycloak-test-runtime-maven-plugin`; see [runtime setup](runtime-plugin.md). Use one runtime per integration-test module; its artifact selects the Keycloak version.
`kc.test.server.extension.quarkus.work` or `kc.test.server.extension.undertow.work` optionally selects the directory retaining child process logs and run files. Maven keeps the resolved server libraries in the local repository.
Admin credentials default to `admin` for both adapters.
The selected server has GLOBAL lifecycle within one Framework registry/test JVM.
Multiple Maven forks have independent servers and databases.
See the [Quarkus integration module](../quarkus-integration-tests/pom.xml) and [Undertow integration module](../undertow-integration-tests/pom.xml) for complete Failsafe configurations.

```java
@KeycloakIntegrationTest
@ResetKeycloakState
public class ExampleIT {
    @InjectRealm(lifecycle = LifeCycle.CLASS, config = Fixture.class)
    ManagedRealm realm;

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

`@ResetKeycloakState`, `@InjectSnapshot`, and `Snapshot` are in `io.github.wadahiro.keycloak.testframework.snapshot`.
Other types are the standard Framework/JUnit types; no extra JUnit extension registration is needed.

## Lifecycle and explicit operations

Each adapter's snapshot supplier uses the Framework's `Supplier.onBeforeEach` and `ManagedTestResource.runCleanup` hooks.
`@ResetKeycloakState` captures the final class baseline after the Framework's `@TestSetup` has run, without requiring a snapshot field.
An `@InjectSnapshot Snapshot` field also enables automatic reset for compatibility and gives tests access to explicit capture and restore operations.
This includes declarative resources such as `@InjectRealm` as well as changes made through the admin API in `@TestSetup`.
After the test and user `@AfterEach`, normal Framework cleanup runs, then the snapshot restores the database and caches.
The next user `@BeforeEach` therefore sees the original baseline.
Class cleanup releases the baseline before the Framework removes that class's realms and clients.
A new class can capture its own fixture on the same running server.

## Combining snapshots with `@InjectRealm`

The Keycloak Test Framework's [Cleanup best practices](https://github.com/keycloak/keycloak/blob/main/test-framework/docs/BEST_PRACTICES.md#cleanup) recommend `lifecycle=CLASS` for performance, with each method cleaning up its own changes.
The guide notes that extensive cleanup can become expensive and harder to read, and suggests `lifecycle=METHOD` when that is the case.
With `@ResetKeycloakState`, tests can keep a class-scoped realm while the extension automatically restores the captured database, supported caches, and registered snapshot participants after each method.
That avoids writing per-test cleanup for covered state and avoids recreating the realm for every method.
This is most useful when method-level changes are broad or made through direct admin API calls; restore time still depends on the amount of database state.
The snapshot does not reset arbitrary external state; custom providers and other resources must implement `SnapshotParticipant` to join the restore.

`@InjectRealm` supports METHOD, CLASS, and GLOBAL lifecycle.
These lifecycles control when the Framework creates and destroys the injected realm resource; they do not each provide a full database rollback.

| Realm lifecycle | Framework behavior | With `@ResetKeycloakState` |
| --- | --- | --- |
| METHOD | Creates a realm for each test method and destroys it afterwards. | Unsupported. Snapshot rejects METHOD-scoped resources because it shares one database baseline across the class. |
| CLASS | Shares a realm within one test class and removes it after the class. This is the default. | Recommended. The post-`@TestSetup` state becomes the class baseline and is restored after every method. |
| GLOBAL | Shares a realm across compatible test classes in one Framework registry and destroys it when the registry closes. | Rejected for stateful resources. A class-specific baseline could otherwise carry setup changes into another class, depending on execution order. |

For isolated test classes, use the default CLASS lifecycle with `@ResetKeycloakState`.
Use `@TestSetup` to create the realm's baseline fixture, then let snapshot restore cover direct admin API changes after each method.
The snapshot covers the whole Keycloak database and supported caches, not just the injected realm.

Do not combine `@ResetKeycloakState` or `@InjectSnapshot` with a GLOBAL realm or other GLOBAL stateful snapshot participant.
The extension fails fast with a message naming the conflicting resource.
Use CLASS lifecycle for snapshot-managed fixtures; this gives each test class its own setup and baseline without depending on class execution order.

`ManagedRealm` also provides helpers such as `updateWithCleanup()` and `updateUserWithCleanup()` that record cleanup operations for the end of each method.
Those helpers work with CLASS and GLOBAL realms; with snapshot reset, use CLASS lifecycle and rely on snapshot restore for database changes.
Direct `realm.admin()` calls are not tracked by those helpers; snapshot restore still reverts their database changes.

Without snapshot reset, METHOD lifecycle remains useful when each method needs a newly created and destroyed realm.
For resources included in a snapshot baseline, use CLASS lifecycle.

Manual operations are available for tests that deliberately need to rebase or reset state during a test:

```java
@InjectSnapshot
Snapshot snapshot;

@TestSetup
public void prepareOnce() {
    // Create/update reusable fixtures through the admin API.
    snapshot.capture(); // Optional: replace the automatic baseline.
}
```

`capture()` replaces the current baseline; it does not create a nested checkpoint.
`restore()` restores the current baseline without consuming it, and automatic cleanup uses that same baseline afterwards.
`getLastRestoreMillis()` reports the most recent complete restore/control round trip.
The API supports one baseline per test class; multiple independently selected baselines are not supported.
The public handle is owned and closed by the Framework.

## What is restored

The current H2 implementation captures detached database rows and restores them with prepared batches into the existing schema.
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
- Use CLASS resources; METHOD-scoped and GLOBAL stateful resources are rejected before capture.
  Do not call `dirty()` on managed resources while using a baseline: recreation conflicts with stable restored IDs.
- JUnit parallel execution is rejected; asynchronous requests/jobs must finish before a method returns.
  Keycloak 23–26 use an HTTP request barrier, while 20–22 have no HTTP barrier.
  The Keycloak timer barrier is available on 26 only; scheduled work on 20–25 must be idle during restore.
- The entire server DB is restored, including master and unrelated realms on this disposable server.
  Do not share this server with other test runners or applications.
- Keep the schema fixed; metadata changes such as table/column/index/foreign-key changes fail reset.
  Arbitrary DDL and non-PUBLIC tables are not supported workloads.
- External stores, files, emails, custom provider state, JVM system properties and arbitrary factory caches are not snapshotted unless an injected resource implements `SnapshotParticipant`.
  The Quarkus integration path supports Keycloak server configuration and TLS.
  Runtime provider artifacts are resolved from the Test Framework server configuration; provider hot deployment is not supported after startup.

## Build and verification

```sh
mvn -Pkeycloak26 -pl quarkus-integration-tests -am verify
mvn -Pundertow15 -pl undertow-integration-tests -am verify
```

The integration tests verify each startup path, the selected server version and JDK, shared realm management, automatic post-`@TestSetup` capture, state restore, and Keycloak cache refresh.
The full version matrix is configured in GitHub Actions.
