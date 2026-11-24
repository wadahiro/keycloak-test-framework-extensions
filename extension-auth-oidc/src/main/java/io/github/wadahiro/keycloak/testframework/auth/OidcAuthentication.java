package io.github.wadahiro.keycloak.testframework.auth;

import org.keycloak.testframework.oauth.OAuthClient;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;

/**
 * Browser-flow adapter for the Framework's OIDC client.
 *
 * <p>Keep using {@code @InjectOAuthClient} to configure the application client. This adapter
 * gives OIDC the same browser entry point as the SAML helper without replacing the Framework's
 * protocol-specific token and authorization response APIs.</p>
 */
public final class OidcAuthentication {
    private final OAuthClient client;
    private final ManagedWebDriver browser;

    public OidcAuthentication(OAuthClient client, ManagedWebDriver browser) {
        this.client = client;
        this.browser = browser;
    }

    /** Starts an authorization-code login journey in the supplied managed browser. */
    public OidcLoginFlow startLogin() {
        client.openLoginForm();
        return new OidcLoginFlow();
    }

    public final class OidcLoginFlow implements InteractiveAuthenticationFlow {
        private OidcLoginFlow() {
        }

        @Override
        public ManagedWebDriver browser() {
            return browser;
        }

        /** Waits for the browser callback and returns the Framework's OIDC authorization result. */
        public OidcAuthorizationResult awaitAuthorizationResponse() {
            var response = client.parseLoginResponse();
            return new OidcAuthorizationResult(response.isRedirected(), response.getCode());
        }
    }
}
