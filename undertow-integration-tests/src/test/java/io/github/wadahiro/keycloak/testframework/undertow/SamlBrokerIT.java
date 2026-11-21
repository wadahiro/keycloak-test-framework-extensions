package io.github.wadahiro.keycloak.testframework.undertow;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.annotations.TestSetup;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.oauth.OAuthClient;
import org.keycloak.testframework.oauth.annotations.InjectOAuthClient;
import org.keycloak.testframework.realm.IdentityProviderBuilder;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.ui.annotations.InjectWebDriver;
import org.keycloak.testframework.ui.annotations.InjectPage;
import org.keycloak.testframework.ui.page.IdpReviewUserProfilePage;
import org.keycloak.testframework.ui.page.LoginPage;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;
import org.openqa.selenium.By;

import io.github.wadahiro.keycloak.testframework.broker.BrokerIdentityLinks;
import io.github.wadahiro.keycloak.testframework.broker.InjectSamlTestIdentityProvider;
import io.github.wadahiro.keycloak.testframework.broker.SamlIdentity;
import io.github.wadahiro.keycloak.testframework.broker.SamlTestIdentityProvider;
import io.github.wadahiro.keycloak.testframework.saml.InjectSamlClient;
import io.github.wadahiro.keycloak.testframework.saml.SamlClient;
import io.github.wadahiro.keycloak.testframework.saml.SamlLoginResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KeycloakIntegrationTest
public class SamlBrokerIT {
    private static final String IDP_ALIAS = "test-saml-idp";
    private static final String REMOTE_SUBJECT = "directory-saml-84";
    private static final String LOCAL_USERNAME = REMOTE_SUBJECT;

    @InjectRealm
    ManagedRealm realm;

    @InjectPage(webDriverRef = "broker-test")
    LoginPage loginPage;

    @InjectOAuthClient(ref = "broker-login", webDriverRef = "broker-test")
    OAuthClient brokerLogin;

    @InjectSamlClient(webDriverRef = "broker-test")
    SamlClient app;

    @InjectWebDriver(ref = "broker-test", lifecycle = LifeCycle.CLASS)
    ManagedWebDriver browser;

    @InjectSamlTestIdentityProvider
    SamlTestIdentityProvider identityProvider;

    @TestSetup
    public void installIdentityProvider() {
        var builder = IdentityProviderBuilder.create().alias(IDP_ALIAS).providerId("saml")
                .enabled(true).firstBrokerLoginFlowAlias("first broker login");
        identityProvider.keycloakConfig().forEach(builder::attribute);
        try (var response = realm.admin().identityProviders().create(builder.build())) {
            assertEquals(201, response.getStatus(), "Failed to install the test SAML broker IdP: "
                    + response.readEntity(String.class));
        }
        realm.cleanup().add(adminRealm -> adminRealm.identityProviders().get(IDP_ALIAS).remove());
    }

    @Test
    void brokeredSamlLoginReturnsValidatedSamlAndCreatesFederatedLink() {
        identityProvider.nextLogin(SamlIdentity.nameId(REMOTE_SUBJECT)
                .attribute("email", "brokered-saml@example.test"));

        boolean keycloak15 = System.getProperty("expected.server.version", "").startsWith("15.");
        if (keycloak15) {
            // Keycloak 15 stores broker and SP request IDs under the same client note.
            // Complete broker login first so the subsequent SP request sets the response correlation ID.
            brokerLogin.openLoginForm();
            BrowserPageAssertions.waitForLoginForm(browser);
            loginPage.clickSocial(IDP_ALIAS);
            completeProfileReviewIfRequired();
            assertTrue(brokerLogin.parseLoginResponse().isRedirected());
        }

        var flow = app.startLogin();
        if (!keycloak15) {
            BrowserPageAssertions.waitForLoginForm(browser);
            loginPage.clickSocial(IDP_ALIAS);
            completeProfileReviewIfRequired();
        }
        SamlLoginResult result = flow.awaitResult(30, TimeUnit.SECONDS);
        assertNotNull(result.nameId());
        var linkedUser = BrokerIdentityLinks.forRealm(realm).findByUsername(LOCAL_USERNAME);
        var link = linkedUser.identityProvider(IDP_ALIAS).orElseThrow();
        assertEquals(REMOTE_SUBJECT, link.externalUserId());
    }

    private void completeProfileReviewIfRequired() {
        BrowserPageAssertions.waitForElement(browser, By.id("firstName"));
        var reviewPage = browser.page().createPage(IdpReviewUserProfilePage.class);
        reviewPage.update("Brokered", "SAML", "brokered-saml@example.test");
    }
}
