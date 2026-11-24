# Isolated runtime Maven plugin

The extension JAR and version-specific runtime JAR contain no Keycloak server libraries.
Keycloak dependencies are resolved by `keycloak-test-runtime-maven-plugin` in Maven's isolated plugin class realm.
The plugin writes their absolute paths to `target/test-classes/META-INF/keycloak-test-runtime.properties`.
The Quarkus extension reads that descriptor and uses the official Quarkus bootstrap APIs to start Keycloak 20–26 in a separate JVM.
The Undertow adapter uses the same prepared classpath to start Keycloak 15.0.2 through its version-specific bootstrap.

Add the extension to the integration-test module's test dependencies and configure one runtime artifact under the Maven plugin's dependencies:

```xml
<plugin>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-runtime-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution>
            <id>prepare-keycloak-runtime</id>
            <phase>process-test-classes</phase>
            <goals>
                <goal>prepare</goal>
            </goals>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>io.github.wadahiro.keycloak.testframework</groupId>
            <artifactId>keycloak-test-runtime-quarkus26</artifactId>
            <version>0.1.0</version>
        </dependency>
    </dependencies>
</plugin>
```

Use `keycloak-test-runtime-quarkus<major>` with the Quarkus extension for Keycloak 20–26.
Do not add the runtime artifact to the project dependencies.
Its normal Maven dependencies are available to the plugin goal but stay outside the IDE and test-runner classpaths.

`mvn verify` runs the bound goal before Failsafe.
For a direct IDE JUnit launch, add a Maven `process-test-classes` Before launch task; this compiles test classes and generates the descriptor before JUnit starts.
A standalone `keycloak-test-runtime:prepare` Maven goal also generates the descriptor when invoked from the configured integration-test module.
Set the JUnit launch JRE independently from `KEYCLOAK_JAVA_HOME`, which selects the child server JDK.

Use one runtime artifact per integration-test module.
When `kc.test.runtime.version` is set, it must match the runtime artifact configured in the plugin.
A mismatch or missing descriptor fails with a message pointing to the Maven plugin setup.
