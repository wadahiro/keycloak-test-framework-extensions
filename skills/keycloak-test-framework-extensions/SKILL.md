---
name: keycloak-test-framework-extensions
description: Use this repository's Keycloak Test Framework extensions to test browser login, SAML clients, OIDC/SAML identity brokering, LDAP federation, and isolated Keycloak runtimes with fast shared-state snapshots.
license: Apache-2.0
metadata:
  runtime: JDK 25 runner with per-version isolated Keycloak server JDKs
---

# Keycloak Test Framework extensions

Use this skill for this repository's extensions or for adding them to another Maven project.
It is not a general Keycloak deployment or upgrade workflow.

## Establish the three runtime boundaries

Inspect the project's POMs and existing test configuration before changing them.
Determine the production/server JDK, the existing unit-test JDK, and the integration-test runner JDK separately.
Preserve the application's current Java target and unit-test runtime.

This extension uses an unmodified modern Keycloak Test Framework in the runner JVM.
It starts server runtimes in separate processes through the selected adapter: Undertow for Keycloak 15.0.2, and the official Quarkus bootstrap for Keycloak 20–26.
Supported server runtimes are Keycloak 15.0.2 on Java 11; 20.0.5, 21.1.2, 22.0.5, 23.0.7 and 24.0.5 on Java 17; 25.0.6 on Java 21; and 26.8.2 on Java 25.
JDK 25 is the preferred runner; the extension bytecode targets Java 21.
Do not describe this as same-JVM embedded execution.

## Integrate

1. Read [references/jdk-isolation.md](references/jdk-isolation.md) when choosing Maven modules, compiler settings, test JVMs, or CI setup.
2. Read [references/extension-api.md](references/extension-api.md) for dependencies, per-version runtime artifacts, service-discovery aliases, and configuration.
3. Read [references/snapshot.md](references/snapshot.md) for automatic snapshot reset.
4. Read [references/ide-testing.md](references/ide-testing.md) when configuring IntelliJ IDEA or Eclipse JUnit launches.
5. Read [the broker testing guide](../../docs/broker-testing-design.md) when testing external OIDC/SAML identity providers, broker mappers, first/post broker flows, or federated identity links.
6. Read [LDAP federation testing](../../docs/ldap-testing.md) when testing LDAP User Federation or LDAP storage mappers.

Use separate Maven modules for Keycloak SPI development and Framework-based integration tests.
The SPI module compiles against the target Keycloak API and its supported JDK; the integration-test module consumes the built SPI artifact and contains the modern Framework/JUnit tests.
This boundary matters because the Framework dependency graph can include newer Keycloak model and server artifacts on the integration-test classpath. Test scope keeps them off the SPI module's main compile classpath, but it does not prevent conflicts if legacy SPI source or tests that compile against old server APIs share the integration-test module.
Prefer Failsafe `*IT` tests in the integration-test module.
Add the extension as a test dependency and configure the selected runtime artifact as a dependency of `keycloak-test-runtime-maven-plugin`; do not add server dependencies to the test classpath or the modern Framework/JUnit stack to the Java 11 unit-test module.
Keep the server GLOBAL.
Use CLASS fixture resources and class-level `@ResetKeycloakState` for automatic reset; inject `@InjectSnapshot Snapshot` only when tests need explicit capture or restore operations.
GLOBAL stateful resources are rejected with snapshots.
Use a disposable server and test realms rather than attaching to an operational realm.

The Java extension implements server lifecycle, H2 configuration, and database/session restoration.
The skill chooses supported options and writes consumer configuration; it should not reimplement those mechanisms with ad hoc XML patches or shell-managed background servers.

Maven binds `keycloak-test-runtime:prepare` to `process-test-classes`. IDE JUnit launches must run that goal before launch so the generated descriptor is present on the test classpath. The IDE JUnit process uses JDK 25, while `KEYCLOAK_JAVA_HOME` selects the child server JDK.

## Verify the intended environment

Run the existing unit tests with their original JDK, and the integration tests with the modern runner and the selected server JDK.
Check the runtime-version assertions/logs as well as test success; `--release 11` alone does not prove Java 11 execution.
Verify the selected DB from the server's `Database info` log.
Distinguish a fresh server/DB from a recreated realm and report what state is actually reset.
Report the Framework version, all relevant JDK versions, DB implementation/version, and untested features.
Do not infer compatibility with Keycloak releases outside the versions listed above.
