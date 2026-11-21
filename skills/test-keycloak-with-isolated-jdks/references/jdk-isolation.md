# Maven and JDK isolation

## Recommended layout

- SPI/provider module: compile against the intended Keycloak SPI version and preserve its supported Java target and unit-test runtime.
- Extension artifacts: modern Java test tooling, included only as test dependencies by consumers.
- Integration-test module: modern Framework and JUnit, Failsafe tests on JDK 25; consumes the built SPI/provider artifact and starts Keycloak on its required server JDK.

Keep the SPI implementation sources out of the Framework integration-test module. Framework dependencies can bring newer Keycloak classes onto that module's test compile classpath; this can cause IDE or Maven conflicts when tests in the same module compile directly against older server APIs. A plain JUnit test is affected by the same classpath if it imports those old APIs, even when it does not use Framework annotations.
If a legacy provider has tests that require its old Keycloak APIs, keep those tests in the SPI module and run them with its original dependency set. Use the integration-test module to test the packaged provider through Keycloak's public authentication flows.

The Maven launcher JVM, compiler JVM, test JVM, and Keycloak JVM can be different.
`maven.compiler.release=11` selects language/API/bytecode compatibility; it does not change the JVM running unit tests.

The executable example in `examples/java11-unit-tests/pom.xml` pins both javac and Surefire to `KEYCLOAK_JAVA_HOME`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <release>11</release>
    <fork>true</fork>
    <executable>${keycloak.java.home}/bin/javac</executable>
  </configuration>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration><jvm>${keycloak.java.home}/bin/java</jvm></configuration>
</plugin>
```

An existing project using Maven Toolchains can retain it instead.
Choose a compiler toolchain and a Surefire/Failsafe JVM explicitly; do not replace unrelated user-wide toolchain settings.
Keep Java 11's older JUnit version in that module; the modern Framework brings its own newer test dependencies to the integration module.

## Commands in this repository

Set `JAVA_HOME` to JDK 25 and `KEYCLOAK_JAVA_HOME` to the selected server JDK (Java 11, 17, 21, or 25).
With mise, install missing versions only when needed:

```sh
mise trust
mise install java@temurin-25 java@temurin-11
export JAVA_HOME="$(mise where java@temurin-25)"
export KEYCLOAK_JAVA_HOME="$(mise where java@temurin-11)" # Set 17, 21, or 25 for newer Keycloak runtimes.
mvn test
mvn verify
```

`test` runs the example unit test on Java 11; Failsafe does not start Keycloak until `verify`.
The integration module still compiles its test sources during `test`, so a whole-reactor build uses the modern Maven launcher.
To run only the existing Java 11 unit-test module, select it explicitly:

```sh
mvn -pl examples/java11-unit-tests test
```

This repository's unit test asserts Java 11 and absence of the Framework from its classpath.
Integration tests assert the selected Keycloak version and server JDK, a modern runner, and server reuse.
The extension checks the configured server JDK's release metadata before launching it.

## CI

Install the selected server JDK first and save its path as `KEYCLOAK_JAVA_HOME`, then select Java 25 as the Maven launcher.
Set `JAVA11_HOME` separately when the existing Java 11 module needs a dedicated compiler or test JVM.
Do not overwrite either saved path when switching `JAVA_HOME`.
The repository workflow demonstrates this order.

## IntelliJ IDEA and Eclipse

Ordinary JUnit launches in IntelliJ IDEA and Eclipse use the integration-test module's Maven test classpath.
Set the JUnit launch JRE to JDK 25 and set `KEYCLOAK_JAVA_HOME` in that launch's environment to the selected Keycloak server JDK.
Run `keycloak-test-runtime:prepare` or the module lifecycle through `process-test-classes` before direct JUnit launches so the child classpath descriptor exists.
Keep the Java 11 module's test dependencies and JUnit launch separate.
See [IDE test execution](ide-testing.md) for detailed steps and troubleshooting.
