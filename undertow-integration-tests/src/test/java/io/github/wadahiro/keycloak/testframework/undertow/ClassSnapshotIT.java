package io.github.wadahiro.keycloak.testframework.undertow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.TestSetup;
import org.keycloak.testframework.realm.ManagedRealm;

import io.github.wadahiro.keycloak.testframework.snapshot.ResetKeycloakState;

@KeycloakIntegrationTest
@ResetKeycloakState
public class ClassSnapshotIT {
    @InjectRealm
    ManagedRealm realm;

    @TestSetup
    public void prepareBaseline() {
        var representation = realm.admin().toRepresentation();
        representation.setDisplayName("class-snapshot-baseline");
        realm.admin().update(representation);
    }

    @Test
    void resetsToClassSetupBaseline() {
        assertEquals("class-snapshot-baseline", realm.admin().toRepresentation().getDisplayName());
        var representation = realm.admin().toRepresentation();
        representation.setDisplayName("changed-by-test");
        realm.admin().update(representation);
    }

    @Test
    void eachMethodStartsFromClassSetupBaseline() {
        assertEquals("class-snapshot-baseline", realm.admin().toRepresentation().getDisplayName());
    }
}
