package io.github.wadahiro.keycloak.testframework.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Jdk11IsolationTest {
    @Test
    void unitTestsActuallyRunOnJava11WithoutTheModernFramework() {
        assertEquals(11, Runtime.version().feature());
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.keycloak.testframework.TestFrameworkExtension"));
        System.out.println("UNIT_JAVA=" + Runtime.version());
    }
}
