package io.github.wadahiro.keycloak.testframework.undertow;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ClientBuilder;
import org.keycloak.testframework.realm.ClientConfig;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.ui.annotations.InjectPage;
import org.keycloak.testframework.ui.page.LoginPage;

import io.github.wadahiro.keycloak.testframework.saml.DefaultSamlClientConfiguration;
import io.github.wadahiro.keycloak.testframework.saml.InjectSamlClient;
import io.github.wadahiro.keycloak.testframework.saml.SamlClient;
import io.github.wadahiro.keycloak.testframework.saml.SamlLoginResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logs in to a SAML client imported with the realm JSON, SP-initiated and IdP-initiated,
 * and checks that the client's own signing, NameID, and mapper settings shape the response.
 */
@KeycloakIntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SamlExistingClientIT {
    private static final String CLIENT_ID = "https://sp.example.test/saml/metadata";
    private static final String PORTAL_CLIENT_ID = "https://portal.example.test/saml";
    private static final String CONFIGURED_ACS = "https://sp.example.test/saml/acs";
    private static final String ACS_ATTRIBUTE = "saml_assertion_consumer_url_post";
    private static final String EMAIL_FORMAT = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress";

    @InjectRealm(fromJson = "/saml-existing-client-realm.json")
    ManagedRealm realm;

    @InjectSamlClient(clientId = CLIENT_ID, lifecycle = LifeCycle.METHOD)
    SamlClient sp;

    @InjectSamlClient(ref = "portal", clientId = PORTAL_CLIENT_ID, lifecycle = LifeCycle.METHOD)
    SamlClient portal;

    @InjectSamlClient(ref = "temporary", config = TemporaryIdpInitiatedClient.class)
    SamlClient temporarySp;

    @InjectPage
    LoginPage loginPage;

    @BeforeEach
    void endPreviousSessions() {
        realm.admin().users().search("alice").forEach(user -> realm.admin().users().get(user.getId()).logout());
    }

    @Test
    @Order(1)
    void spInitiatedLoginUsesTheImportedClientSettings() {
        var flow = sp.startLogin();
        login(flow);
        SamlLoginResult result = flow.awaitResult(30, TimeUnit.SECONDS);
        assertEquals("alice@example.test", result.nameId());
        assertEquals(EMAIL_FORMAT, result.nameIdFormat());
        assertEquals(List.of("Alice"), result.attributes().get("givenName"));
        assertNotNull(result.sessionIndex());
        assertNotNull(result.relayState());
    }

    @Test
    @Order(2)
    void idpInitiatedLoginReturnsTheConfiguredRelayState() {
        var flow = sp.startIdpInitiatedLogin();
        login(flow);
        SamlLoginResult result = flow.awaitResult(30, TimeUnit.SECONDS);
        assertEquals("alice@example.test", result.nameId());
        assertEquals(EMAIL_FORMAT, result.nameIdFormat());
        assertEquals(List.of("Alice"), result.attributes().get("givenName"));
        assertEquals("/dashboard", result.relayState());
    }

    @Test
    @Order(3)
    void idpInitiatedLoginAcceptsAnExplicitRelayState() {
        var flow = sp.startIdpInitiatedLogin("/reports");
        login(flow);
        assertEquals("/reports", flow.awaitResult(30, TimeUnit.SECONDS).relayState());
    }

    @Test
    @Order(4)
    void temporaryClientSupportsIdpInitiatedLogin() {
        var flow = temporarySp.startIdpInitiatedLogin();
        login(flow);
        SamlLoginResult result = flow.awaitResult(30, TimeUnit.SECONDS);
        assertEquals("alice", result.nameId());
        assertNull(result.relayState());
    }

    @Test
    @Order(5)
    void closeRestoresTheImportedClient() {
        ClientRepresentation overridden = client();
        assertEquals(Set.of(CONFIGURED_ACS, sp.getAcsUrl()), Set.copyOf(overridden.getRedirectUris()));
        assertEquals(sp.getAcsUrl(), overridden.getAttributes().get(ACS_ATTRIBUTE));

        sp.close();

        ClientRepresentation restored = client();
        assertEquals(List.of(CONFIGURED_ACS), restored.getRedirectUris());
        assertEquals(CONFIGURED_ACS, restored.getAttributes().get(ACS_ATTRIBUTE));
        assertEquals("example-sp", restored.getAttributes().get("saml_idp_initiated_sso_url_name"));
        assertEquals("/dashboard", restored.getAttributes().get("saml_idp_initiated_sso_relay_state"));
        assertEquals("true", restored.getAttributes().get("saml.assertion.signature"));
        assertTrue(restored.getProtocolMappers().stream().anyMatch(mapper -> "given name".equals(mapper.getName())));
    }

    @Test
    @Order(6)
    void closeRemovesTheAcsSettingsAClientDidNotHave() {
        assertEquals(List.of(portal.getAcsUrl()), client(PORTAL_CLIENT_ID).getRedirectUris());

        portal.close();

        ClientRepresentation restored = client(PORTAL_CLIENT_ID);
        assertTrue(restored.getRedirectUris() == null || restored.getRedirectUris().isEmpty(),
                () -> "Unexpected redirect URIs " + restored.getRedirectUris());
        String acs = restored.getAttributes().get(ACS_ATTRIBUTE);
        assertTrue(acs == null || acs.isEmpty(), () -> "Unexpected ACS URL " + acs);
    }

    private ClientRepresentation client() {
        return client(CLIENT_ID);
    }

    private ClientRepresentation client(String clientId) {
        String id = realm.admin().clients().findByClientId(clientId).get(0).getId();
        return realm.admin().clients().get(id).toRepresentation();
    }

    private void login(SamlClient.SamlLoginFlow flow) {
        BrowserPageAssertions.waitForLoginForm(flow.browser());
        loginPage.fillLogin("alice", "alice-password");
        loginPage.submit();
    }

    public static final class TemporaryIdpInitiatedClient implements ClientConfig {
        @Override
        public ClientBuilder configure(ClientBuilder client) {
            return new DefaultSamlClientConfiguration().configure(client)
                    .attribute("saml_idp_initiated_sso_url_name", "temporary-sp");
        }
    }
}
