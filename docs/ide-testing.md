# Running integration tests from an IDE

IntelliJ IDEA and Eclipse can run these tests with their normal JUnit launchers.
The extension must be a test dependency in the integration-test module. Configure the selected `keycloak-test-runtime-quarkus<major>` artifact as a dependency of `keycloak-test-runtime-maven-plugin`, not as a project dependency.
The Maven plugin writes a descriptor into `target/test-classes`; its isolated dependency classpath never enters the IDE JUnit classpath. The extension starts Keycloak in a separate JVM when the test framework requests the GLOBAL server.
No Keycloak installation or IDE plugin is required.

## Java versions

Use JDK 25 for the IDE's JUnit process, which runs the modern Keycloak Test Framework and this extension.
Set `KEYCLOAK_JAVA_HOME` in the IDE run configuration to the JDK for the selected Keycloak version. The integration test module selects Chrome headless with `kc.test.browser=chrome-headless`:

| Keycloak | `KEYCLOAK_JAVA_HOME` |
| --- | --- |
| 20.0.5–24.0.5 | JDK 17 |
| 25.0.6 | JDK 21 |
| 26.8.2 | JDK 25 |

This setting affects only the child Keycloak process.
It does not change the JDK used by the IDE's JUnit process.
Keep existing Java 11 unit tests in their existing module and run configuration; do not add the integration-test dependencies to that module.

## IntelliJ IDEA

1. Import the project as a Maven project and refresh Maven after changing dependencies.
2. Set the project SDK (or the integration-test module SDK) to JDK 25.
3. Create a JUnit run configuration for the integration test class or package.
4. Set the configuration's JRE to JDK 25.
5. Add `KEYCLOAK_JAVA_HOME` to the environment variables, pointing to the selected server JDK.
6. Keep the working directory at the project root so relative test resources and Maven module paths resolve consistently.

Use the IDE's JUnit configuration to run selected tests directly after the Maven Before launch task prepares the descriptor.
In the JUnit run configuration’s **Before launch**, add a Maven goal for `-pl <integration-test-module> process-test-classes`; the configured plugin generates the descriptor before direct JUnit execution. Use a Maven run configuration for `verify` when Failsafe lifecycle behavior is needed; Failsafe runs `*IT` tests during `verify`, not `test`.

## Eclipse

1. Import the project with **File > Import > Existing Maven Projects** and update the Maven project after changing dependencies.
2. Set the workspace or integration-test project's installed JRE to JDK 25.
3. Open **Run Configurations > JUnit** and create a launch for the test class or package.
4. On the **JRE** tab, select JDK 25.
5. On the **Environment** tab, add `KEYCLOAK_JAVA_HOME` with the selected server JDK path.
6. Run an Eclipse Maven Build launch for `-pl <integration-test-module> process-test-classes` before JUnit, or connect it through External Tools for automatic pre-launch execution.
7. Keep the working directory at the project root.

Use a JUnit launch for selected tests.
Use **Run As > Maven build...** with the `verify` goal when Maven Failsafe lifecycle behavior is required.

## Troubleshooting

- `No Keycloak runtime classpath is prepared`: run the Maven `keycloak-test-runtime:prepare` goal or the module lifecycle through `process-test-classes`, then refresh the IDE test classpath.
- `does not match kc.test.runtime.version`: choose the same version in the plugin dependency and optional runtime-version check.
- `Set KEYCLOAK_JAVA_HOME...`: add the environment variable to the IDE's JUnit run configuration; the shell or Maven environment is not automatically inherited by every IDE launch.
- `requires JDK <n>`: point `KEYCLOAK_JAVA_HOME` at a JDK of the required feature version, not at the IDE's JDK unless those versions happen to match.
- Runtime startup failures: inspect the run directory reported by the exception, especially `server.log`.

See [architecture](architecture.md) for the process boundary and [snapshot lifecycle](snapshot.md) for state reset behavior.
