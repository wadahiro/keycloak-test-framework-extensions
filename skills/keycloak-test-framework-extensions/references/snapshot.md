# Snapshot reset

Use `keycloak-test-framework-undertow` for Keycloak 15.0.2 or `keycloak-test-framework-quarkus` for Keycloak 20–26, with test scope in an isolated integration-test module.
Import the Keycloak Test Framework BOM that matches the selected server version.

Select the matching server and admin suppliers for the adapter: `undertow-process` and `undertow-admin` for 15.0.2, or `quarkus-process` and `quarkus-admin` for 20–26.
Configure the matching runtime artifact under `keycloak-test-runtime-maven-plugin` dependencies.
Use Java 11 for the 15.0.2 server, Java 17 for 20–24, Java 21 for 25, or Java 25 for 26, while keeping the integration-test runner isolated from existing unit-test JVMs.

Inject `@InjectSnapshot Snapshot snapshot` from `io.github.wadahiro.keycloak.testframework.snapshot`.
Keep fixture resources CLASS/GLOBAL; METHOD resources and JUnit parallel execution are rejected.
A baseline is captured after declarative resources and Framework `@TestSetup` complete, then restored after user `@AfterEach`, before the next `@BeforeEach`.
`capture()` replaces the class baseline; `restore()` can reset immediately within a method.
Do not call managed-resource `dirty()` or perform schema changes while using this mode.
Prefer direct admin operations over `WithCleanup` helpers to avoid extra per-method admin-API cleanup calls.

Java classes own database-specific row copy, prepared-batch restore, cache handling, request/timer barriers, and process lifecycle.
The skill configures these mechanisms; do not implement competing reset scripts.
The whole disposable database is restored, including master and unrelated realms.
Injected resources implementing `SnapshotParticipant`, including the UnboundID LDAP fixture, capture and restore with the database baseline.
User/client sessions present at capture are deep-copied and restored, while later sessions disappear.
Time is not rewound; external resources and arbitrary provider state are not restored unless a managed resource implements `SnapshotParticipant`.
Wait for asynchronous work to finish; reset failure stops the child and requires a fresh server/test JVM.

The public test API is independent of the database backend; current runtime implementations use H2.
Validate the selected profile with `mvn -Pkeycloak26 -pl quarkus-integration-tests -am verify` or `mvn -Pundertow15 -pl undertow-integration-tests -am verify`.
