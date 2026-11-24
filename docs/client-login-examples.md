# Testing browser login flows

These APIs are intentionally Keycloak test helpers, not general-purpose OIDC or SAML client libraries. Their purpose is to exercise Keycloak realm/client/user configuration and custom Keycloak SPIs through a real browser authentication flow.
The dependency examples omit versions because they are managed by the extensions BOM; see [Getting started](getting-started.md) for Maven setup.

| Concern | OIDC | SAML |
| --- | --- | --- |
| API | Framework `@InjectOAuthClient` and `OAuthClient` | Extension `@InjectSamlClient` and `SamlClient` |
| Start browser flow | `oauth.loginForm().open()` | `saml.startLogin()` |
| Interactive steps | Framework `ManagedWebDriver` and page objects | `flow.browser()` and Framework page objects |
| Result assertions | Tokens and OIDC claims | Validated NameID, session index, and SAML attributes |
| Application-specific session | Test the application's own callback separately | Test the actual SP separately |

Reuse the Framework OIDC helper instead of adding another OIDC abstraction. Add the extension-specific helper only for the SAML SP/ACS behavior missing from the Framework.

## OIDC

The Framework's `@InjectOAuthClient` performs the authorization redirect, browser login, authorization-code callback, token exchange, and user-info request.

```java
@KeycloakIntegrationTest
class OidcBrowserLoginIT {
    @InjectUser(config = LoginUser.class)
    ManagedUser user;

    @InjectOAuthClient(config = LoginClient.class)
    OAuthClient oidc;

    @InjectPage
    LoginPage loginPage;

    @Test
    void configuredAuthenticatorReturnsTheAuthenticatedUser() {
        oidc.loginForm().open();
        loginPage.assertCurrent();
        loginPage.fillLogin(user.getUsername(), user.getPassword());
        loginPage.submit();
        var authorization = oidc.parseLoginResponse();
        assertTrue(authorization.isRedirected());
        var tokens = oidc.doAccessTokenRequest(authorization.getCode());
        assertTrue(tokens.isSuccess(), tokens.getErrorDescription());
        var userInfo = oidc.doUserInfoRequest(tokens.getAccessToken());
        assertEquals(user.getUsername(), userInfo.getUserInfo().getPreferredUsername());
    }
}
```

Add `org.keycloak.testframework:keycloak-test-framework-oauth` as a test dependency. Configure the OAuth client and test user with the Framework's `ClientConfig` and `UserConfig` interfaces.

## SAML

Add `io.github.wadahiro.keycloak.testframework:keycloak-test-framework-saml` as a test dependency. `@InjectSamlClient` creates a temporary SAML client, reads the realm's IdP metadata, generates a Redirect-binding AuthnRequest, runs the browser login, receives the POST-binding response on a local dynamic-port ACS, and validates the response signature and protocol fields before exposing the result.

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

For custom authenticators, OTP/WebAuthn, consent, required actions, or custom login themes, use the interactive APIs: open the OIDC `loginForm()` or start the SAML flow, then drive the appropriate Framework page objects/browser interactions and inspect the protocol result. Configure the realm authentication flow, required actions, users, and custom SPI through managed resources or Keycloak Admin API; these helpers provide the protocol-facing test client.

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

For a custom authenticator or required action, create a Framework `AbstractPage` subclass for its screen and call `browser.page().createPage(YourPage.class)`, or use `browser.driver()` directly with Selenium. The flow helper does not assume a fixed sequence of screens; the test drives each expected step and waits for the final ACS response. OIDC tests can use the same page objects with `oauth.loginForm().open()` and finish with `oauth.parseLoginResponse()`.

The default SAML client requires Keycloak to sign its response and assertion and uses the realm's published signing certificate for validation. Provide a custom `ClientConfig` through `@InjectSamlClient(config = ...)` for client-specific settings. The current API focuses on SP-initiated login and assertion validation; logout, encrypted assertions, and IdP-initiated login are outside its scope.

This helper validates the assertion before returning identity data, but it does not implement an application's session, authorization policy, or full production SP behavior. For application acceptance tests, run the application/SP stack and assert its own authenticated session and authorization decisions.

For broker tests where Keycloak redirects to an external OIDC or SAML IdP, see the [broker authentication test guide](broker-testing-design.md). It covers deterministic IdP fixtures, first/post broker flows, mappers, and federated identity link assertions.

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

oauth.openLoginForm();
loginPage.clickSocial("partner");
// Drive any profile review, custom authenticator, consent, or required-action pages here.
var authorization = oauth.parseLoginResponse();
assertTrue(authorization.isRedirected());

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
