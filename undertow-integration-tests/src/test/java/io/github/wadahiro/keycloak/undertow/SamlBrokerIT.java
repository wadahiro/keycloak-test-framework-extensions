package io.github.wadahiro.keycloak.undertow;

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

import io.github.wadahiro.keycloak.broker.BrokerIdentityLinks;
import io.github.wadahiro.keycloak.broker.InjectSamlTestIdentityProvider;
import io.github.wadahiro.keycloak.broker.SamlIdentity;
import io.github.wadahiro.keycloak.broker.SamlTestIdentityProvider;
import io.github.wadahiro.keycloak.saml.InjectSamlClient;
import io.github.wadahiro.keycloak.saml.SamlClient;
import io.github.wadahiro.keycloak.saml.SamlLoginResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@KeycloakIntegrationTest
public class SamlBrokerIT {
    private static final String IDP_ALIAS = "test-saml-idp";
    private static final String REMOTE_SUBJECT = "directory-saml-84";
    private static final String LOCAL_USERNAME = REMOTE_SUBJECT;

    @InjectRealm
    ManagedRealm realm;

    @InjectPage(webDriverRef = "broker-test")
    LoginPage loginPage;

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

        var flow = app.startLogin();
        loginPage.assertCurrent();
        loginPage.clickSocial(IDP_ALIAS);
        completeProfileReviewIfRequired();

        SamlLoginResult result = flow.awaitResult(30, TimeUnit.SECONDS);
        assertNotNull(result.nameId());
        var linkedUser = BrokerIdentityLinks.forRealm(realm).findByUsername(LOCAL_USERNAME);
        var link = linkedUser.identityProvider(IDP_ALIAS).orElseThrow();
        assertEquals(REMOTE_SUBJECT, link.externalUserId());
    }

    private void completeProfileReviewIfRequired() {
        if ("login-idp-review-user-profile".equals(browser.page().getCurrentPageId())) {
            var reviewPage = browser.page().createPage(IdpReviewUserProfilePage.class);
            reviewPage.assertCurrent();
            reviewPage.update("Brokered", "SAML", "brokered-saml@example.test");
        }
    }
}
