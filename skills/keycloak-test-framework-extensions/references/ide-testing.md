# IDE test execution

Both IntelliJ IDEA and Eclipse can run integration tests through their ordinary JUnit launchers.
Import the SPI/provider and integration-test modules as separate Maven projects/modules. Run legacy SPI unit tests from the SPI module; run Framework tests from the integration-test module, which consumes the packaged SPI/provider artifact.
Do not place old-Keycloak SPI sources or tests that compile against old server APIs in the integration-test module: its test classpath includes the Framework dependency graph and may expose newer Keycloak classes to the IDE compiler.
Keep the extension in project test scope and configure one matching runtime artifact under `keycloak-test-runtime-maven-plugin` dependencies.
Run `process-test-classes` before direct JUnit launches to generate the descriptor; runtime libraries stay outside the test classpath.

Configure the IDE's JUnit process to use JDK 25.
Set `KEYCLOAK_JAVA_HOME` in the JUnit launch environment to the server JDK: 17 for Keycloak 20–24, 21 for 25, or 25 for 26.
Configure exactly one runtime artifact per integration-test module; it selects the Keycloak version.
The adapter supplies default `kc.test.server` and `kc.test.keycloak` selections, which users may override in `keycloak-test.properties` when choosing different suppliers.

Keep legacy Java 11 unit tests in their existing module and run configuration.
Do not add the modern Keycloak Test Framework or the extension to that module's test dependencies.
Use an IDE Maven launch with `verify` when Maven Failsafe lifecycle behavior is needed; direct JUnit runs do not invoke Maven lifecycle phases.
See [runtime plugin setup](../../../docs/runtime-plugin.md) for a consumer POM example.

Detailed launch setup and troubleshooting are in [the repository IDE guide](../../../docs/ide-testing.md).
