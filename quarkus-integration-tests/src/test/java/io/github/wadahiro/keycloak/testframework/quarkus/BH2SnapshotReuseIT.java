package io.github.wadahiro.keycloak.testframework.quarkus;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.testframework.annotations.*;
import org.keycloak.testframework.realm.ManagedRealm;
import static org.junit.jupiter.api.Assertions.*;

@KeycloakIntegrationTest
class BH2SnapshotReuseIT {
    @InjectRealm ManagedRealm realm;
    @InjectAdminClient Keycloak admin;
    @InjectQuarkusH2Snapshot QuarkusH2Snapshot snapshot;
    @Test void newClassHasFreshBaselineAndSharesTheSameServer() throws Exception {
        assertEquals(Long.parseLong(Files.readString(AH2SnapshotIT.work().resolve("snapshot-pid.txt"))), snapshot.getServerPid());
        String oldRealm = Files.readString(AH2SnapshotIT.work().resolve("snapshot-realm.txt"));
        assertTrue(admin.realms().findAll().stream().noneMatch(rep -> oldRealm.equals(rep.getRealm())));
        assertTrue(realm.admin().users().search("baseline-user").isEmpty());
        var rep = realm.admin().toRepresentation(); rep.setDisplayName("new-class-baseline"); realm.admin().update(rep);
        snapshot.capture();
        rep.setDisplayName("dirty"); realm.admin().update(rep);
        snapshot.restore();
        assertEquals("new-class-baseline", realm.admin().toRepresentation().getDisplayName());
    }
}
