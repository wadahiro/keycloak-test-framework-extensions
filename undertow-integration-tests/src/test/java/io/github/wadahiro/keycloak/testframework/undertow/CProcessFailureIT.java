package io.github.wadahiro.keycloak.testframework.undertow;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CProcessFailureIT {
    @Test void runtimeFailureStopsTheChildAndRejectsFurtherUse() {
        UndertowProcessServer server = new UndertowProcessServer();
        try {
            server.start(null, false);
            long pid = server.pid();
            server.command("CAPTURE", UUID.randomUUID().toString());
            assertThrows(IllegalStateException.class, () -> server.command("RESTORE", UUID.randomUUID().toString()));
            assertFalse(server.isRunning());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertThrows(IllegalStateException.class, () -> server.command("CAPTURE", UUID.randomUUID().toString()));
        } finally { server.stop(); }
    }
    @Test void rejectsWrongServerJdkBeforeStartingAChild() {
        String previous = System.getProperty("kc.test.server.extension.undertow.java.home");
        try {
            System.setProperty("kc.test.server.extension.undertow.java.home", System.getProperty("expected.server.java").equals("11") ? System.getProperty("java.home") : System.getProperty("unit.java.home"));
            UndertowProcessServer server = new UndertowProcessServer();
            assertThrows(IllegalStateException.class, () -> server.start(null, false));
            assertFalse(server.isRunning());
        } finally {
            if (previous == null) System.clearProperty("kc.test.server.extension.undertow.java.home");
            else System.setProperty("kc.test.server.extension.undertow.java.home", previous);
        }
    }
}
