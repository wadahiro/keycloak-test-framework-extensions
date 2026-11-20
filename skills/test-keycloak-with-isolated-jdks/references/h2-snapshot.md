# H2 snapshot reset

Use `io.github.wadahiro:keycloak-test-framework-undertow:0.1.0-SNAPSHOT` with test scope in the modern integration-test module.
Import `org.keycloak.testframework:keycloak-test-framework-bom:26.8.2` in the consumer dependency management.
Source/local-install artifacts are not published releases until the maintainer publishes them.

Select `kc.test.server=undertow-process` and `kc.test.keycloak=undertow-admin` in `keycloak-test.properties` or Failsafe system properties.
For Keycloak 20–26, add the matching `keycloak-test-runtime-undertow<major>` test artifact.
Set `KEYCLOAK_JAVA_HOME` or `kc.test.undertow.java.home` to the server JDK required by that version, preserving the JDK 25 runner and the existing unit-test JVM.
The extension starts the selected runtime in a separate JVM.

Inject `@InjectH2Snapshot H2Snapshot snapshot` (both types in `io.github.wadahiro.keycloak.undertow`).
Keep fixture resources CLASS/GLOBAL; METHOD resources and JUnit parallel execution are rejected.
A baseline is automatically captured after declarative resources exist and restored by Framework cleanup after user `@AfterEach`, before the next `@BeforeEach`.
For imperative setup, call `snapshot.capture()` at the end of a public `@TestSetup` method on a public test class.
`capture()` replaces the class baseline; `restore()` can reset immediately within a method.
Do not call managed-resource `dirty()` or perform schema changes while using this mode.
Prefer direct admin operations over `WithCleanup` helpers to avoid additional per-method admin-API cleanup calls.

Java classes own the database row copy, prepared-batch restore, cache handling, request/timer barrier and process lifecycle.
The skill configures these supported mechanisms; do not implement competing restore scripts.
The whole disposable DB is restored, including master and unrelated realms.
User/client sessions present at capture are deep-copied and restored, while later sessions disappear; this preserves administrative sessions without a password grant after every reset.
Model/revision/key caches are evicted; transient authentication-session, login-failure and action-token caches are cleared.
Time is not rewound and external resources/arbitrary provider state are not restored.
Wait for all asynchronous work; reset failure stops the child and requires a fresh server/test JVM.

Validate with `mvn -pl undertow-integration-tests -am verify` for the integration module and `mvn verify` for the full build.
