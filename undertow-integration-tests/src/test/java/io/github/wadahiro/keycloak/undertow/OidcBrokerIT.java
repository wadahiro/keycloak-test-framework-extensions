package io.github.wadahiro.keycloak.undertow;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.annotations.TestSetup;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.oauth.OAuthClient;
import org.keycloak.testframework.oauth.annotations.InjectOAuthClient;
import org.keycloak.testframework.realm.IdentityProviderBuilder;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.testframework.ui.annotations.InjectWebDriver;
import org.keycloak.testframework.ui.annotations.InjectPage;
import org.keycloak.testframework.ui.page.IdpReviewUserProfilePage;
import org.keycloak.testframework.ui.page.LoginUpdateProfilePage;
import org.keycloak.testframework.ui.page.LoginPage;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;

import io.github.wadahiro.keycloak.broker.BrokerIdentityLinks;
import io.github.wadahiro.keycloak.broker.InjectOidcTestIdentityProvider;
import io.github.wadahiro.keycloak.broker.OidcIdentity;
import io.github.wadahiro.keycloak.broker.OidcTestIdentityProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@KeycloakIntegrationTest
public class OidcBrokerIT {
    private static final String IDP_ALIAS = "test-oidc-idp";
    private static final String LOCAL_USERNAME = "brokered-oidc-user";
    private static final String REMOTE_SUBJECT = "directory-oidc-42";
    private static final String FIRST_LOGIN_FLOW = "test-first-broker-login";

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
                .enabled(true).firstBrokerLoginFlowAlias(FIRST_LOGIN_FLOW);
        identityProvider.keycloakConfig("test-broker-client", "test-broker-secret")
                .forEach(builder::attribute);
        try (var response = realm.admin().flows().copy(
                realm.admin().toRepresentation().getFirstBrokerLoginFlow(), Map.of("newName", FIRST_LOGIN_FLOW))) {
            assertEquals(201, response.getStatus(), "Failed to copy the first broker login flow");
        }
        AuthenticationExecutionInfoRepresentation profileReview = realm.admin().flows().getExecutions(FIRST_LOGIN_FLOW)
                .stream().filter(execution -> "idp-review-profile".equals(execution.getProviderId()))
                .findFirst().orElse(null);
        assertNotNull(profileReview, "Copied first broker flow has no profile review execution");
        profileReview.setRequirement("DISABLED");
        realm.admin().flows().updateExecutions(FIRST_LOGIN_FLOW, profileReview);
        try (var response = realm.admin().identityProviders().create(builder.build())) {
            assertEquals(201, response.getStatus(), "Failed to install the test OIDC broker IdP: "
                    + response.readEntity(String.class));
        }
        realm.cleanup().add(adminRealm -> adminRealm.identityProviders().get(IDP_ALIAS).remove());

        IdentityProviderMapperRepresentation mapper = new IdentityProviderMapperRepresentation();
        mapper.setName("department-claim-to-user-attribute");
        mapper.setIdentityProviderAlias(IDP_ALIAS);
        mapper.setIdentityProviderMapper("oidc-user-attribute-idp-mapper");
        mapper.setConfig(Map.of("syncMode", "IMPORT", "claim", "department", "user.attribute", "firstName"));
        try (var response = realm.admin().identityProviders().get(IDP_ALIAS).addMapper(mapper)) {
            assertEquals(201, response.getStatus(), "Failed to add the department claim mapper");
        }
    }

    @Test
    void brokeredOidcLoginMapsIdentityAndCreatesFederatedLink() {
        identityProvider.nextLogin(OidcIdentity.subject(REMOTE_SUBJECT)
                .claim("preferred_username", LOCAL_USERNAME)
                .claim("email", "brokered-oidc@example.test")
                .claim("email_verified", true)
                .claim("department", "engineering"));

        app.openLoginForm();
        loginPage.assertCurrent();
        assertTrue(!browser.cookies().getAll().isEmpty(), "Keycloak login did not establish browser cookies");
        loginPage.clickSocial(IDP_ALIAS);
        completeProfileReviewIfRequired();
        completeProfileRequiredActionIfPresent();

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
        var user = realm.admin().users().search(LOCAL_USERNAME).get(0);
        assertEquals("engineering", user.getFirstName());
    }

    private void completeProfileReviewIfRequired() {
        if ("login-idp-review-user-profile".equals(browser.page().getCurrentPageId())) {
            var reviewPage = browser.page().createPage(IdpReviewUserProfilePage.class);
            reviewPage.assertCurrent();
            reviewPage.update("Brokered", "OIDC", "brokered-oidc@example.test");
        }
    }

    private void completeProfileRequiredActionIfPresent() {
        if (browser.getCurrentUrl().contains("execution=VERIFY_PROFILE")) {
            var profilePage = browser.page().createPage(LoginUpdateProfilePage.class);
            assertEquals("engineering", profilePage.getFirstName(),
                    "The department claim mapper should set the local first name before required actions run");
            profilePage.update(profilePage.getFirstName(), "OIDC", "brokered-oidc@example.test");
        }
    }
}
