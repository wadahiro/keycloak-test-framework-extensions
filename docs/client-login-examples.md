# Testing browser login flows

These APIs are intentionally Keycloak test helpers, not general-purpose OIDC or SAML client libraries. Their purpose is to exercise Keycloak realm/client/user configuration and custom Keycloak SPIs through a real browser authentication flow.
The dependency examples omit versions because they are managed by the extensions BOM; see [Getting started](getting-started.md) for Maven setup.

| Journey | Entry point | Completion/assertion |
| --- | --- | --- |
| Browser login, custom authentication screens, Required Actions | `InteractiveAuthenticationFlow.browser()` | Protocol-specific OIDC or SAML result |
| Registration and password reset | Start browser login, then follow configured registration/reset links | Assert user state and finish the OIDC/SAML callback when applicable |
| Brokered login | Start app-facing OIDC/SAML login; a local external IdP fixture answers the broker redirect | Assert mapped user data and `BrokerIdentityLinks` |
| Direct Grant | Framework `OAuthClient.doPasswordGrantRequest(...)` | Assert token response and user info; no browser is involved |
| Service account/client credentials | Framework `OAuthClient` client-credentials grant helper | Assert service-account token and roles; no browser is involved |
| OIDC logout | Framework OIDC logout helper | Assert the protocol result and application session separately |

The shared flow interface standardizes browser access, not protocol results or client setup.
OIDC continues to use the Framework's `OAuthClient` and token APIs.
SAML uses the extension's `SamlClient` and validated assertion result.
Both expose the same `ManagedWebDriver` so tests can interact with pages and redirects without assuming a fixed screen sequence.

## OIDC

The Framework's `@InjectOAuthClient` performs the authorization redirect, browser login, authorization-code callback, token exchange, and user-info request.

```java
@KeycloakIntegrationTest
class OidcBrowserLoginIT {
    @InjectUser(config = LoginUser.class)
    ManagedUser user;

    @InjectOAuthClient(config = LoginClient.class, webDriverRef = "login")
    OAuthClient oidc;

    @InjectWebDriver(ref = "login")
    ManagedWebDriver browser;

    @Test
    void configuredAuthenticatorReturnsTheAuthenticatedUser() {
        var flow = new OidcAuthentication(oidc, browser).startLogin();
        LoginPage loginPage = flow.browser().page().createPage(LoginPage.class);
        loginPage.assertCurrent();
        loginPage.fillLogin(user.getUsername(), user.getPassword());
        loginPage.submit();
        var authorization = flow.awaitAuthorizationResponse();
        assertTrue(authorization.redirected());
        var tokens = oidc.doAccessTokenRequest(authorization.code());
        assertTrue(tokens.isSuccess(), tokens.getErrorDescription());
        var userInfo = oidc.doUserInfoRequest(tokens.getAccessToken());
        assertEquals(user.getUsername(), userInfo.getUserInfo().getPreferredUsername());
    }
}
```

Add `org.keycloak.testframework:keycloak-test-framework-oauth` and `io.github.wadahiro.keycloak.testframework:keycloak-test-framework-auth-oidc` as test dependencies.
Configure the OAuth client and test user with the Framework's `ClientConfig` and `UserConfig` interfaces.
The adapter shares browser-flow interaction with SAML while preserving the Framework's OIDC token APIs.

## SAML

Add `io.github.wadahiro.keycloak.testframework:keycloak-test-framework-saml` as a test dependency.
`@InjectSamlClient` creates a temporary SAML client, reads the realm's IdP metadata, generates a Redirect-binding AuthnRequest, runs the browser login, receives the POST-binding response on a local dynamic-port ACS, and validates the response signature and protocol fields before exposing the result.

```java
@KeycloakIntegrationTest
class SamlBrowserLoginIT {
    @InjectUser(config = LoginUser.class)
    ManagedUser user;

    @InjectSamlClient
    SamlClient saml;

    @InjectPage
    LoginPage loginPage;

    @Test
    void browserLoginReturnsAValidatedAssertion() {
        var flow = saml.startLogin();
        loginPage.assertCurrent();
        loginPage.fillLogin(user.getUsername(), user.getPassword());
        loginPage.submit();
        SamlLoginResult result = flow.awaitResult(30, TimeUnit.SECONDS);
        assertEquals(user.getUsername(), result.nameId());
        assertNotNull(result.sessionIndex());
    }
}
```

Use `startLogin()` and `flow.awaitResult(timeout, unit)` when a test needs to inspect or control the login page, exercise required actions, or drive additional browser steps. `doLogin(username, password)` is the short path for the normal username/password flow. The validated result also exposes `attributes()` so tests can assert configured SAML claims.

For custom authenticators, OTP/WebAuthn, consent, required actions, or custom login themes, use the interactive API and drive Framework page objects/browser interactions before inspecting the protocol result.
Registration and password reset are browser journeys that begin from Keycloak's login UI; follow the configured links and pages rather than assuming identical markup across themes.
For reset links delivered by email, configure the Framework test email server and inspect the message there.
Configure realm flows, required actions, users, and custom SPI through managed resources or the Keycloak Admin API.

Direct Grant is a separate OAuth request, not a browser journey.
The Framework's `doPasswordGrantRequest(username, password)` exercises the realm's Direct Grant flow; it cannot test browser authenticators, registration, password reset, themes, or user interaction.
Use browser login for those requirements.

For example, when the configured client requires consent, the test can assert and accept the intermediate consent screen before checking the final protocol result:

```java
var flow = saml.startLogin();
var browser = flow.browser();

LoginPage loginPage = browser.page().createPage(LoginPage.class);
loginPage.assertCurrent();
loginPage.fillLogin(user.getUsername(), user.getPassword());
loginPage.submit();

OAuthGrantPage consentPage = browser.page().createPage(OAuthGrantPage.class);
consentPage.assertCurrent();
consentPage.assertGrants(OAuthGrantPage.PROFILE_CONSENT_TEXT);
consentPage.accept();

SamlLoginResult result = flow.awaitResult(30, TimeUnit.SECONDS);
```

For a custom authenticator or required action, create a Framework `AbstractPage` subclass for its screen and call `browser.page().createPage(YourPage.class)`, or use `browser.driver()` directly with Selenium. The flow helper does not assume a fixed sequence of screens; the test drives each expected step and waits for the final protocol response. OIDC tests start with `OidcAuthentication.startLogin()` and finish with `awaitAuthorizationResponse()`; token exchange remains on the injected Framework `OAuthClient`.

The default SAML client requires Keycloak to sign its response and assertion and uses the realm's published signing certificate for validation. Provide a custom `ClientConfig` through `@InjectSamlClient(config = ...)` for client-specific settings. The current API focuses on SP-initiated login and assertion validation; SAML logout, encrypted assertions, and IdP-initiated login are outside its scope.

This helper validates the assertion before returning identity data, but it does not implement an application's session, authorization policy, or full production SP behavior. For application acceptance tests, run the application/SP stack and assert its own authenticated session and authorization decisions.

For broker tests where Keycloak redirects to an external OIDC or SAML IdP, see the [broker authentication test guide](broker-testing-design.md). It covers deterministic IdP fixtures, first/post broker flows, mappers, and federated identity link assertions.

Device Authorization Grant and CIBA are non-browser journeys that can be added as protocol-specific APIs later. Account-console initiated actions and logout are adjacent journeys; test them explicitly when they are part of the realm requirements. They do not change the shared browser-flow contract.

## Broker mapper and flow checks

Use the protocol-specific fixture only for the external IdP response.
Configure the IdP, mapper, and broker flow with Keycloak's Admin API, then start the normal app-facing OIDC or SAML login.

```java
IdentityProviderMapperRepresentation mapper = new IdentityProviderMapperRepresentation();
mapper.setName("department-claim-to-user-attribute");
mapper.setIdentityProviderMapper("oidc-user-attribute-idp-mapper");
mapper.setConfig(Map.of(
        "syncMode", "IMPORT",
        "claim", "department",
        "user.attribute", "firstName"));
realm.admin().identityProviders().get("partner").addMapper(mapper).close();

idp.nextLogin(OidcIdentity.subject("directory-42")
        .claim("preferred_username", "alice")
        .claim("department", "engineering"));

var flow = new OidcAuthentication(oauth, browser).startLogin();
loginPage.clickSocial("partner");
// Drive any profile review, custom authenticator, consent, or required-action pages here.
var authorization = flow.awaitAuthorizationResponse();
assertTrue(authorization.redirected());

var user = realm.admin().users().search("alice").get(0);
assertEquals("engineering", user.getFirstName());
assertEquals("directory-42", BrokerIdentityLinks.forRealm(realm)
        .findByUsername("alice").identityProvider("partner").orElseThrow().externalUserId());
```

For a custom first broker login flow, copy the realm's default flow with `realm.admin().flows().copy(...)`, give it a test-specific alias, and pass that alias to `IdentityProviderBuilder.firstBrokerLoginFlowAlias(...)`.
For a post broker login check, set `postBrokerLoginFlowAlias(...)` and use an already-linked local user so the test exercises the returning-user path.
The OIDC integration example maps the external `department` claim to the built-in `firstName` property and completes the resulting `VERIFY_PROFILE` required action before checking the final user.
See `OidcPostBrokerFlowIT` for a complete example that submits credentials on the additional authenticator screen before validating the client response.

The integration examples use a local OIDC or SAML IdP fixture and a real browser.
The browser remains in the Keycloak flow while the fixture answers the external IdP request, so it can exercise both broker configuration and intermediate Keycloak UI steps.
