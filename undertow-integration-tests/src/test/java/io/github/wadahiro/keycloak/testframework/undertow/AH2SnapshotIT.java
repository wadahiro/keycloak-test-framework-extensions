package io.github.wadahiro.keycloak.testframework.undertow;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Map;
import io.github.wadahiro.keycloak.testframework.snapshot.InjectSnapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.Snapshot;
import org.junit.jupiter.api.*;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.*;
import org.keycloak.testframework.annotations.*;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.*;
import org.keycloak.util.JsonSerialization;
import static org.junit.jupiter.api.Assertions.*;

@KeycloakIntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AH2SnapshotIT {
    @InjectRealm(lifecycle = LifeCycle.CLASS, config = Fixture.class) ManagedRealm realm;
    @InjectAdminClient Keycloak admin;
    @InjectSnapshot Snapshot snapshot;
    static String originalUserId, originalClientId, oldRefreshToken;
    static String masterDisplayName;

    public static class Fixture implements RealmConfig {
        @Override public RealmBuilder configure(RealmBuilder builder) {
            return builder.name("snapshot-fixture").displayName("declarative-baseline").users(UserBuilder.create("baseline-user").enabled(true).password("password").firstName("Baseline").lastName("User").email("baseline@example.test"))
                    .clients(ClientBuilder.create("snapshot-client").enabled(true).publicClient().directAccessGrantsEnabled());
        }
    }
    @TestSetup public void prepareOnce() throws Exception {
        var rep = realm.admin().toRepresentation(); rep.setDisplayName("setup-baseline"); realm.admin().update(rep);
        originalUserId = realm.admin().users().search("baseline-user").getFirst().getId();
        originalClientId = realm.admin().clients().findByClientId("snapshot-client").getFirst().getId();
        masterDisplayName = admin.realm("master").toRepresentation().getDisplayName();
        Files.writeString(work().resolve("snapshot-realm.txt"), realm.getName());
    }
    @BeforeEach void baselineIsReadyBeforeUserSetup() {
        assertEquals("setup-baseline", realm.admin().toRepresentation().getDisplayName());
        assertEquals(originalUserId, realm.admin().users().search("baseline-user").getFirst().getId());
        assertEquals(originalClientId, realm.admin().clients().findByClientId("snapshot-client").getFirst().getId());
        assertTrue(realm.admin().users().search("extra-user").isEmpty());
        assertTrue(realm.admin().clients().findByClientId("extra-client").isEmpty());
        assertEquals(0, realm.admin().users().get(originalUserId).getUserSessions().size());
        assertEquals(masterDisplayName, admin.realm("master").toRepresentation().getDisplayName());
    }
    @Test @Order(1) void mutateRealmUsersClientsCredentialsAndSessions() throws Exception {
        assertEquals(System.getProperty("expected.server.version"), admin.serverInfo().getInfo().getSystemInfo().getVersion());
        assertTrue(admin.serverInfo().getInfo().getSystemInfo().getJavaVersion().startsWith(System.getProperty("expected.server.java") + "."));
        assertEquals(25, Runtime.version().feature());
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.github.wadahiro.keycloak.testframework.runtime15.RuntimeMain"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.github.wadahiro.keycloak.testframework.runtimemodern.RuntimeMain"));
        Map<?, ?> token = JsonSerialization.readValue(token("grant_type=password&username=baseline-user&password=password").body(), Map.class);
        oldRefreshToken = (String) token.get("refresh_token"); assertNotNull(oldRefreshToken);
        var rep = realm.admin().toRepresentation(); rep.setDisplayName("changed"); realm.admin().update(rep);
        var user = realm.admin().users().get(originalUserId).toRepresentation(); user.setFirstName("changed");
        realm.admin().users().get(originalUserId).update(user);
        var credential = new CredentialRepresentation(); credential.setType("password"); credential.setValue("changed-password"); credential.setTemporary(false);
        realm.admin().users().get(originalUserId).resetPassword(credential);
        var extra = new UserRepresentation(); extra.setUsername("extra-user"); extra.setEnabled(true);
        try (var response = realm.admin().users().create(extra)) { assertEquals(201, response.getStatus()); }
        var client = new ClientRepresentation(); client.setClientId("extra-client"); client.setEnabled(true);
        try (var response = realm.admin().clients().create(client)) { assertEquals(201, response.getStatus()); }
        var baselineClient = realm.admin().clients().get(originalClientId).toRepresentation(); baselineClient.setEnabled(false);
        realm.admin().clients().get(originalClientId).update(baselineClient);
        var master = admin.realm("master").toRepresentation(); master.setDisplayName("changed-master"); admin.realm("master").update(master);
        // Warm the mutated caches, so a DB-only restore cannot pass the next method's checks.
        assertEquals("changed", realm.admin().toRepresentation().getDisplayName());
        assertEquals("changed", realm.admin().users().get(originalUserId).toRepresentation().getFirstName());
        assertFalse(realm.admin().clients().get(originalClientId).toRepresentation().isEnabled());
    }
    @AfterEach void cleanupOccursAfterUserAfterEach(TestInfo info) {
        if (info.getTestMethod().orElseThrow().getName().equals("mutateRealmUsersClientsCredentialsAndSessions")) {
            assertEquals("changed", realm.admin().toRepresentation().getDisplayName());
        }
    }
    @Test @Order(2) void nextMethodHasOriginalObjectsAndRejectsOldRefreshToken() throws Exception {
        assertTrue(snapshot.getLastRestoreMillis() > 0);
        assertEquals("Baseline", realm.admin().users().get(originalUserId).toRepresentation().getFirstName());
        var refresh = token("grant_type=refresh_token&refresh_token=" + oldRefreshToken);
        assertEquals(400, refresh.statusCode(), refresh.body()); assertTrue(refresh.body().contains("invalid_grant"));
        assertEquals(200, token("grant_type=password&username=baseline-user&password=password").statusCode());
        realm.admin().users().get(originalUserId).remove();
        realm.admin().clients().get(originalClientId).remove();
    }
    @Test @Order(3) void deletedBaselineObjectsAndCredentialsAreRestoredWithSameIds() throws Exception {
        assertEquals(200, token("grant_type=password&username=baseline-user&password=password").statusCode());
        System.out.println("H2_SNAPSHOT_LAST_RESTORE_MS=" + snapshot.getLastRestoreMillis());
        assertEquals(1, realm.admin().users().count());
    }
    HttpResponse<String> token(String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(realm.getBaseUrl() + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body + "&client_id=snapshot-client")).build(), HttpResponse.BodyHandlers.ofString());
            if (body.startsWith("grant_type=password")) assertEquals(200, response.statusCode(), response.body());
            return response;
        }
    }
    static Path work() { return Path.of(System.getProperty("kc.test.undertow.work")); }
}
