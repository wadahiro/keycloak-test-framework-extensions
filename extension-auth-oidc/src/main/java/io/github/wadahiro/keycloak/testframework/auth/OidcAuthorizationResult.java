package io.github.wadahiro.keycloak.testframework.auth;

/** Result of the browser authorization-code callback in an OIDC journey. */
public record OidcAuthorizationResult(boolean redirected, String code) {
}
