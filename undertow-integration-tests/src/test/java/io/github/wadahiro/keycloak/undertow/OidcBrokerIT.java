package io.github.wadahiro.keycloak.undertow;

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
import io.github.wadahiro.keycloak.broker.InjectOidcTestIdentityProvider;
import io.github.wadahiro.keycloak.broker.OidcIdentity;
import io.github.wadahiro.keycloak.broker.OidcTestIdentityProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KeycloakIntegrationTest
public class OidcBrokerIT {
    private static final String IDP_ALIAS = "test-oidc-idp";
    private static final String LOCAL_USERNAME = "brokered-oidc-user";
    private static final String REMOTE_SUBJECT = "directory-oidc-42";

    @InjectRealm
    ManagedRealm realm;

    @InjectOAuthClient(webDriverRef = "broker-test")
    OAuthClient app;

    @InjectPage(webDriverRef = "broker-test")
    LoginPage loginPage;

    @InjectWebDriver(ref = "broker-test", lifecycle = LifeCycle.CLASS)
    ManagedWebDriver browser;

    @InjectOidcTestIdentityProvider
    OidcTestIdentityProvider identityProvider;

    @TestSetup
    public void installIdentityProvider() {
        var builder = IdentityProviderBuilder.create().alias(IDP_ALIAS).providerId("oidc")
                .enabled(true).firstBrokerLoginFlowAlias("first broker login");
        identityProvider.keycloakConfig("test-broker-client", "test-broker-secret")
                .forEach(builder::attribute);
        try (var response = realm.admin().identityProviders().create(builder.build())) {
            assertEquals(201, response.getStatus(), "Failed to install the test OIDC broker IdP: "
                    + response.readEntity(String.class));
        }
        realm.cleanup().add(adminRealm -> adminRealm.identityProviders().get(IDP_ALIAS).remove());
    }

    @Test
    void brokeredOidcLoginMapsIdentityAndCreatesFederatedLink() {
        identityProvider.nextLogin(OidcIdentity.subject(REMOTE_SUBJECT)
                .claim("preferred_username", LOCAL_USERNAME)
                .claim("email", "brokered-oidc@example.test")
                .claim("email_verified", true)
                .claim("given_name", "Brokered")
                .claim("family_name", "OIDC"));

        app.openLoginForm();
        loginPage.assertCurrent();
        assertTrue(!browser.cookies().getAll().isEmpty(), "Keycloak login did not establish browser cookies");
        loginPage.clickSocial(IDP_ALIAS);
        completeProfileReviewIfRequired();

        var authorization = app.parseLoginResponse();
        assertTrue(authorization.isRedirected(), "Current URL: " + browser.getCurrentUrl() + "\n"
                + browser.driver().getPageSource());
        var tokens = app.doAccessTokenRequest(authorization.getCode());
        assertTrue(tokens.isSuccess(), tokens.getErrorDescription());
        var userInfo = app.doUserInfoRequest(tokens.getAccessToken());
        assertTrue(userInfo.isSuccess());
        assertEquals(LOCAL_USERNAME, userInfo.getUserInfo().getPreferredUsername());
        assertEquals("brokered-oidc@example.test", userInfo.getUserInfo().getEmail());

        var linkedUser = BrokerIdentityLinks.forRealm(realm).findByUsername(LOCAL_USERNAME);
        var link = linkedUser.identityProvider(IDP_ALIAS).orElseThrow();
        assertEquals(REMOTE_SUBJECT, link.externalUserId());
        assertEquals(LOCAL_USERNAME, link.externalUsername());
    }

    private void completeProfileReviewIfRequired() {
        if ("login-idp-review-user-profile".equals(browser.page().getCurrentPageId())) {
            var reviewPage = browser.page().createPage(IdpReviewUserProfilePage.class);
            reviewPage.assertCurrent();
            reviewPage.update("Brokered", "OIDC", "brokered-oidc@example.test");
        }
    }
}
