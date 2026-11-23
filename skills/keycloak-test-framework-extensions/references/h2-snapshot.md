# H2 snapshot reset

Use `io.github.wadahiro.keycloak.testframework:keycloak-test-framework-quarkus:0.1.0-SNAPSHOT` with test scope in the integration-test module.
Import the Keycloak Test Framework BOM that matches the selected server version.

Select `kc.test.server=quarkus-process` and `kc.test.keycloak=quarkus-admin` in `keycloak-test.properties` or Failsafe system properties.
Configure the matching `keycloak-test-runtime-quarkus<major>` artifact under `keycloak-test-runtime-maven-plugin` dependencies.
Set `KEYCLOAK_JAVA_HOME` to the server JDK, preserving the JDK 25 runner and the existing unit-test JVM.

Inject `@InjectQuarkusH2Snapshot QuarkusH2Snapshot snapshot` from `io.github.wadahiro.keycloak.testframework.quarkus`.
Keep fixture resources CLASS/GLOBAL; METHOD resources and JUnit parallel execution are rejected.
A baseline is captured after declarative resources exist and restored after user `@AfterEach`, before the next `@BeforeEach`.
For imperative setup, call `snapshot.capture()` at the end of a public `@TestSetup` method on a public test class.
`capture()` replaces the class baseline; `restore()` can reset immediately within a method.
Do not call managed-resource `dirty()` or perform schema changes while using this mode.
Prefer direct admin operations over `WithCleanup` helpers to avoid extra per-method admin-API cleanup calls.

Java classes own the database row copy, prepared-batch restore, cache handling, request/timer barrier, and process lifecycle.
The skill configures these mechanisms; do not implement competing reset scripts.
The whole disposable DB is restored, including master and unrelated realms.
Injected resources implementing `H2SnapshotParticipant`, including the UnboundID LDAP fixture, capture and restore alongside the H2 baseline.
User/client sessions present at capture are deep-copied and restored, while later sessions disappear.
Time is not rewound; external resources and arbitrary provider state are not restored unless a managed resource implements `H2SnapshotParticipant`.
Wait for asynchronous work to finish; reset failure stops the child and requires a fresh server/test JVM.

Validate with `mvn -Pkeycloak26 -pl quarkus-integration-tests -am verify` for the integration module.
