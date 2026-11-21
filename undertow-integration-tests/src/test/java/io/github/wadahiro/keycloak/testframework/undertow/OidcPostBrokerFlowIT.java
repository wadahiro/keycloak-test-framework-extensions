package io.github.wadahiro.keycloak.testframework.undertow;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.AuthenticationExecutionRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.InjectUser;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.annotations.TestSetup;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.oauth.OAuthClient;
import org.keycloak.testframework.oauth.annotations.InjectOAuthClient;
import org.keycloak.testframework.realm.FederatedIdentityBuilder;
import org.keycloak.testframework.realm.IdentityProviderBuilder;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.ManagedUser;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.realm.UserConfig;
import org.keycloak.testframework.util.ApiUtil;
import org.keycloak.testframework.ui.annotations.InjectPage;
import org.keycloak.testframework.ui.annotations.InjectWebDriver;
import org.keycloak.testframework.ui.page.LoginPage;
import org.keycloak.testframework.ui.page.PasswordPage;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;

import io.github.wadahiro.keycloak.testframework.broker.InjectOidcTestIdentityProvider;
import io.github.wadahiro.keycloak.testframework.broker.OidcIdentity;
import io.github.wadahiro.keycloak.testframework.broker.OidcTestIdentityProvider;
import org.openqa.selenium.By;

import static org.junit.jupiter.api.Assertions.assertTrue;

@KeycloakIntegrationTest
public class OidcPostBrokerFlowIT {
    private static final String IDP_ALIAS = "test-post-login-idp";
    private static final String POST_LOGIN_FLOW = "test-post-broker-login";
    private static final String REMOTE_SUBJECT = "linked-directory-account";
    private static final String USERNAME = "already-linked-user";
    private static final String PASSWORD = "local-user-password";

    @InjectRealm
    ManagedRealm realm;

    @InjectUser(config = ExistingLinkedUser.class)
    ManagedUser user;

    @InjectOAuthClient(webDriverRef = "post-broker")
    OAuthClient app;

    @InjectPage(webDriverRef = "post-broker")
    LoginPage loginPage;

    @InjectWebDriver(ref = "post-broker", lifecycle = LifeCycle.CLASS)
    ManagedWebDriver browser;

    @InjectOidcTestIdentityProvider
    OidcTestIdentityProvider identityProvider;

    @TestSetup
    public void configurePostBrokerFlow() {
        AuthenticationFlowRepresentation flow = new AuthenticationFlowRepresentation();
        flow.setAlias(POST_LOGIN_FLOW);
        flow.setProviderId("basic-flow");
        flow.setTopLevel(true);
        flow.setBuiltIn(false);
        String flowId;
        try (var response = realm.admin().flows().createFlow(flow)) {
            assertTrue(response.getStatus() == 201, "Failed to create post broker flow: " + response.getStatus());
            flowId = ApiUtil.getCreatedId(response);
        }

        AuthenticationExecutionRepresentation execution = new AuthenticationExecutionRepresentation();
        execution.setParentFlow(flowId);
        execution.setAuthenticator("auth-username-password-form");
        execution.setRequirement("REQUIRED");
        execution.setPriority(0);
        try (var response = realm.admin().flows().addExecution(execution)) {
            assertTrue(response.getStatus() == 201, "Failed to add post broker authenticator: " + response.getStatus());
        }
        var builder = IdentityProviderBuilder.create().alias(IDP_ALIAS).providerId("oidc").enabled(true)
                .firstBrokerLoginFlowAlias("first broker login")
                .postBrokerLoginFlowAlias(POST_LOGIN_FLOW);
        identityProvider.keycloakConfig("post-broker-client", "post-broker-secret")
                .forEach(builder::attribute);
        try (var response = realm.admin().identityProviders().create(builder.build())) {
            assertTrue(response.getStatus() == 201, "Failed to install the test OIDC IdP: " + response.getStatus());
        }
        realm.cleanup().add(adminRealm -> adminRealm.identityProviders().get(IDP_ALIAS).remove());
        realm.cleanup().add(adminRealm -> adminRealm.flows().deleteFlow(flowId));

        user.admin().addFederatedIdentity(IDP_ALIAS,
                FederatedIdentityBuilder.create(IDP_ALIAS, REMOTE_SUBJECT, USERNAME).build());
        assertTrue(user.admin().getFederatedIdentity().stream()
                .anyMatch(identity -> IDP_ALIAS.equals(identity.getIdentityProvider())),
                "The test user must already be linked to the test IdP");
    }

    @Test
    void linkedBrokerLoginRunsConfiguredPostLoginAuthenticator() {
        identityProvider.nextLogin(OidcIdentity.subject(REMOTE_SUBJECT)
                .claim("preferred_username", USERNAME)
                .claim("email", "already-linked@example.test")
                .claim("email_verified", true));

        app.openLoginForm();
        BrowserPageAssertions.waitForLoginForm(browser);
        loginPage.clickSocial(IDP_ALIAS);

        // The external IdP has returned; the configured post-broker authenticator asks for local credentials.
        assertTrue(browser.driver().findElements(By.id("password")).size() > 0,
                "Expected the post-broker password page, page=" + browser.page().getCurrentPageId()
                        + ", url=" + browser.getCurrentUrl());
        var usernameField = browser.driver().findElements(By.id("username"));
        if (!usernameField.isEmpty()) {
            usernameField.get(0).sendKeys(USERNAME);
        }
        PasswordPage passwordPage = browser.page().createPage(PasswordPage.class);
        passwordPage.fillPassword(user.getPassword());
        passwordPage.submit();
        BrowserPageAssertions.waitForUrlToLeave(browser, "/login-actions/post-broker-login");

        var authorization = app.parseLoginResponse();
        assertTrue(authorization.isRedirected(), "Post-broker flow did not return to the OIDC client: "
                + browser.getCurrentUrl());
        var tokens = app.doAccessTokenRequest(authorization.getCode());
        assertTrue(tokens.isSuccess(), tokens.getErrorDescription());
    }

    public static class ExistingLinkedUser implements UserConfig {
        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username(USERNAME).password(PASSWORD).name("Already", "Linked")
                    .email("already-linked@example.test");
        }
    }
}
