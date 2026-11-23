package io.github.wadahiro.keycloak.testframework.quarkus;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.keycloak.testframework.annotations.*;
import org.keycloak.testframework.realm.*;
import org.keycloak.util.JsonSerialization;
import static org.junit.jupiter.api.Assertions.*;

@KeycloakIntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DBaselineSessionIT {
    @InjectRealm(config = AH2SnapshotIT.Fixture.class) ManagedRealm realm;
    @InjectQuarkusH2Snapshot QuarkusH2Snapshot snapshot;
    static String refreshToken, userId, sessionId;
    @TestSetup public void captureSessionFixture() throws Exception {
        var response = token("grant_type=password&username=baseline-user&password=password");
        assertEquals(200, response.statusCode(), response.body());
        refreshToken = (String) JsonSerialization.readValue(response.body(), Map.class).get("refresh_token");
        userId = realm.admin().users().search("baseline-user").getFirst().getId();
        sessionId = realm.admin().users().get(userId).getUserSessions().getFirst().getId();
        snapshot.capture();
    }
    @Test @Order(1) void logOutTheBaselineSession() {
        realm.admin().logoutAll();
        assertTrue(realm.admin().users().get(userId).getUserSessions().isEmpty());
    }
    @Test @Order(2) void restoresDeepCopiedSessionAndItsRefreshToken() throws Exception {
        assertEquals(sessionId, realm.admin().users().get(userId).getUserSessions().getFirst().getId());
        var response = token("grant_type=refresh_token&refresh_token=" + refreshToken);
        assertEquals(200, response.statusCode(), response.body());
    }
    HttpResponse<String> token(String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(realm.getBaseUrl() + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body + "&client_id=snapshot-client")).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
