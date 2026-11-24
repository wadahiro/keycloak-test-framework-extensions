package io.github.wadahiro.keycloak.testframework.quarkus;

import io.github.wadahiro.keycloak.testframework.snapshot.InjectSnapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.Snapshot;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectAdminClient;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import static org.junit.jupiter.api.Assertions.*;

@KeycloakIntegrationTest
class QuarkusBootstrapIT {
    @InjectRealm(lifecycle = LifeCycle.CLASS) ManagedRealm realm;
    @InjectAdminClient Keycloak admin;
    @InjectSnapshot Snapshot snapshot;

    @Test void frameworkManagesARealmOnOfficialQuarkusBootstrap() {
        assertNotNull(realm.admin().toRepresentation().getId());
        var systemInfo = admin.serverInfo().getInfo().getSystemInfo();
        assertEquals(System.getProperty("expected.server.version"), systemInfo.getVersion());
        assertTrue(systemInfo.getJavaVersion().startsWith(System.getProperty("expected.server.java") + "."));
    }

    @Test void h2SnapshotRestoresDatabaseAndKeycloakCaches() {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("snapshot-mutation");
        user.setEnabled(true);
        try (var response = realm.admin().users().create(user)) {
            assertEquals(201, response.getStatus());
        }
        assertEquals(1, realm.admin().users().search("snapshot-mutation").size());
        snapshot.restore();
        assertTrue(realm.admin().users().search("snapshot-mutation").isEmpty());
        assertTrue(snapshot.getLastRestoreMillis() > 0);
        System.out.printf("QUARKUS_H2_RESTORE_MS=%.3f%n", snapshot.getLastRestoreMillis());
    }
}
