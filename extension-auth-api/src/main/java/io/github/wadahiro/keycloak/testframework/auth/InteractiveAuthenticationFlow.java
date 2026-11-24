package io.github.wadahiro.keycloak.testframework.auth;

import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;

/**
 * A browser-driven Keycloak authentication journey.
 *
 * <p>The journey deliberately does not prescribe screens or protocol. Tests can drive login,
 * registration, password reset, required actions, custom authenticators, themes, and broker
 * redirects through the same Framework-managed browser, then use the protocol-specific client
 * to inspect the result.</p>
 */
public interface InteractiveAuthenticationFlow {

    /** Returns the browser currently positioned in this authentication journey. */
    ManagedWebDriver browser();
}
