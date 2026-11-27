# Testing behind a reverse proxy

Deployments often put a reverse proxy in front of Keycloak.
The proxy may terminate mutual TLS and forward the client certificate subject in a request header, and it reports the client address in `X-Forwarded-For`.
Custom authenticators and required actions then decide by such a header or by `ClientConnection#getRemoteAddr()`.

A browser driven by Selenium cannot add request headers, and the managed server is reached directly on the loopback address.
The front proxy closes that gap: it runs in the test JVM, forwards HTTP to the managed Keycloak server, and adds the headers the test configures.

```xml
<dependency>
    <groupId>io.github.wadahiro.keycloak.testframework</groupId>
    <artifactId>keycloak-test-framework-proxy</artifactId>
    <scope>test</scope>
</dependency>
```

The version is managed by the extensions BOM; see [Getting started](getting-started.md).

## Trust the forwarded headers

Keycloak uses forwarded headers only when it is configured to trust them.
Set the Keycloak server option `proxy-headers=xforwarded` in the test's server configuration:

```java
public final class ProxiedServerConfig implements KeycloakServerConfig {
    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return config.dependency("com.example", "my-keycloak-provider")
                .option("proxy-headers", "xforwarded");
    }
}
```

| Runtime | What the option does |
| --- | --- |
| Keycloak 24–26 (Quarkus) | Passed through as `--proxy-headers=xforwarded` |
| Keycloak 20–23 (Quarkus) | Translated to `--proxy=edge`, since these releases have no `proxy-headers`; an explicit `proxy` option takes precedence |
| Keycloak 15.0.2 (Undertow) | Enables Undertow's proxy address forwarding, the equivalent of WildFly's `proxy-address-forwarding="true"`; only `xforwarded` is accepted |

The server is shared by every test class with the same server configuration, so the option belongs to that configuration rather than to the proxy.
Without the option, Keycloak builds its URLs from the server's own address and ignores `X-Forwarded-Host`, `X-Forwarded-Port` and `X-Forwarded-Proto`.

## Inject the proxy

```java
@KeycloakIntegrationTest(config = ProxiedServerConfig.class)
class ClientCertificateHeaderIT {
    @InjectUser(config = AliceUser.class)
    ManagedUser user;

    @InjectFrontProxy
    FrontProxy proxy;

    @InjectOAuthClient(webDriverRef = "proxied")
    OAuthClient oauth;

    @InjectWebDriver(ref = "proxied")
    ManagedWebDriver browser;

    @InjectPage(webDriverRef = "proxied")
    LoginPage loginPage;

    @Test
    void authenticatorReadsTheForwardedSubject() {
        proxy.header("X-Client-Cert-Subject", "CN=alice,O=Example").clientAddress("10.0.0.15");
        oauth.baseUrl(proxy.getBaseUrl());

        oauth.openLoginForm();
        loginPage.fillLogin(user.getUsername(), user.getPassword());
        loginPage.submit();

        var authorization = oauth.parseLoginResponse();
        assertTrue(authorization.isRedirected());
    }

    public static class AliceUser implements UserConfig {
        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username("alice").password("alice-password").name("Alice", "Example")
                    .email("alice@example.test");
        }
    }
}
```

`getBaseUrl()` is the proxy URL that corresponds to the server's base URL, and `getRealmUrl(realm)` the URL of a realm.
Point the Framework `OAuthClient` at it with `oauth.baseUrl(proxy.getBaseUrl())`; its login redirect, token and user-info requests then pass through the proxy.
Use the same URLs for a `java.net.http.HttpClient` or any other client.

Each forwarded request carries:

- `X-Forwarded-For`: the address set with `clientAddress(...)`, or the connecting client's address. A comma-separated chain such as `198.51.100.7, 203.0.113.10` is sent as given; Keycloak reports its left-most entry.
- `X-Forwarded-Host`, `X-Forwarded-Port` and `X-Forwarded-Proto` (`http`, or `https` with [TLS](#https)): the proxy's own address, so Keycloak builds redirects, form actions, cookie paths and the token issuer for the proxy.
- The headers set with `header(name, value)`. A configured header replaces one of the same name sent by the client, as a proxy does that does not let clients supply it.

`stripHeader(name)` drops a header the client sends, and `removeHeader(name)` stops adding a configured one.
Headers, stripped headers and the client address apply to the following requests of the current test; the supplier resets them before each test.
`X-Forwarded-*` and `Forwarded` headers sent by the client are always replaced.
The proxy does not rewrite bodies or `Location` headers; Keycloak emits the proxy's URLs itself once it trusts the forwarded headers.

The default lifecycle is CLASS; use `ref` to inject more than one proxy in a test class.

## Client address and SSL requirements

A realm's default SSL requirement, "external requests", treats loopback and private addresses as local.
Through a plain HTTP proxy, a public client address such as `203.0.113.10` makes Keycloak demand HTTPS, so a browser login from that address stops at "HTTPS required".
Use an HTTPS proxy (below) for browser flows from a public address; the integration tests complete such a login on Keycloak 15.0.2 and 26.
With plain HTTP, use a private address (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) or set the test realm's SSL requirement to `none`.
Requests that do not check the SSL requirement, such as a custom realm resource, can use any address.

## HTTPS

`@InjectFrontProxy(tls = true)` makes the proxy listen on HTTPS, like a proxy that terminates TLS in front of Keycloak.
Each proxy generates its own self-signed RSA certificate for `127.0.0.1` and `localhost`.
It forwards plain HTTP to Keycloak with `X-Forwarded-Proto: https` and the TLS port in `X-Forwarded-Port`, so Keycloak builds `https://` URLs for the proxy and sets secure cookies.
The default stays plain HTTP.

Clients must trust the generated certificate:

- `proxy.sslContext()` returns a client `SSLContext` that trusts it, for `java.net.http.HttpClient.newBuilder().sslContext(...)` or Apache HttpClient's `HttpClients.custom().setSSLContext(...)`.
  `getCertificate()` and `getTrustStore()` expose the certificate and a PKCS #12 trust store holding it.
- The Framework `OAuthClient` sends its token and user-info requests with its own HTTP client, which trusts only the JDK defaults.
  Replace it for the test with `oauth.httpClient().set(HttpClients.custom().setSSLContext(proxy.sslContext()).build())` and restore it with `oauth.httpClient().reset()`.
- Browsers: the Framework's `chrome-headless` browser runs with `--ignore-certificate-errors` and needs no configuration.
  For the `htmlunit` browser (the Framework default when `kc.test.browser` is not set), call `FrontProxyBrowser.trust(browser, proxy)` before the first request; it installs the proxy's trust through HtmlUnit's public `WebClientOptions#setSSLContext`.
  `FrontProxyBrowser.trust` does nothing for `chrome-headless`. The headed `chrome` browser and Firefox show a certificate error page; use `chrome-headless` or `htmlunit`.
  `FrontProxyBrowser` needs the Framework UI module on the test classpath, which browser tests already have.

```java
@InjectFrontProxy(tls = true)
FrontProxy proxy;

@Test
void loginFromAPublicAddress() throws Exception {
    proxy.clientAddress("203.0.113.10");
    FrontProxyBrowser.trust(browser, proxy);
    oauth.baseUrl(proxy.getBaseUrl());
    try (var backChannel = HttpClients.custom().setSSLContext(proxy.sslContext()).build()) {
        oauth.httpClient().set(backChannel);
        oauth.openLoginForm();
        loginPage.fillLogin(user.getUsername(), user.getPassword());
        loginPage.submit();
        var tokens = oauth.doAccessTokenRequest(oauth.parseLoginResponse().getCode());
        assertTrue(tokens.isSuccess());
    } finally {
        oauth.httpClient().reset();
    }
}
```

## Mutual TLS

A proxy that terminates mutual TLS validates the client certificate and forwards it to Keycloak in request headers.
Configure that on the annotation:

```java
@InjectFrontProxy(tls = true, clientAuth = FrontProxy.ClientAuth.WANT,
        clientCertificateSubjectHeader = "X-Client-Cert-Subject",
        clientCertificateHeader = "X-Client-Cert")
FrontProxy proxy;
```

- `clientAuth`: `WANT` requests a certificate and also accepts clients without one; `NEED` fails the TLS handshake without one; `NONE` (default) requests none.
- `clientCertificateSubjectHeader`: the presented certificate's subject DN in RFC 2253 form, for example `CN=alice,O=Example`.
- `clientCertificateHeader`: the presented certificate as URL-encoded PEM, the form nginx forwards as `$ssl_client_escaped_cert` and Keycloak's `nginx` X.509 client certificate lookup reads.

The proxy accepts any client certificate; the test decides which one to present.
It always removes these headers from client requests, so a client cannot forge them, and adds them only when a certificate was presented.
A header set with `header(name, value)` takes precedence, which simulates a certificate for clients that cannot present one.

`ClientCertificate.generate("CN=alice,O=Example")` creates a self-signed client certificate and key, and `proxy.sslContext(certificate)` returns a client context that presents it:

```java
var certificate = ClientCertificate.generate("CN=alice,O=Example");
try (var client = HttpClient.newBuilder().sslContext(proxy.sslContext(certificate)).build()) {
    // requests to proxy.getRealmUrl(...) now carry X-Client-Cert-Subject: CN=alice,O=Example
}
```

Browsers:

- `htmlunit` can present the certificate: `FrontProxyBrowser.presentClientCertificate(browser, proxy, certificate)` installs it through `WebClientOptions#setSSLContext`, without changing the Framework.
- `chrome-headless` cannot present a client certificate without user interaction; use `proxy.header("X-Client-Cert-Subject", "CN=alice,O=Example")` with `clientAuth = WANT` to simulate what the proxy would forward.

## Limitations

- The proxy speaks HTTP/1.1 on `127.0.0.1` and forwards to the server over HTTP; it does not proxy WebSocket upgrades.
- With TLS the certificate is self-signed and generated per proxy; clients must trust it explicitly as described above.
  Client certificates are not validated against a CA, and revocation is not checked.
- Request and response bodies are buffered in memory.
- On Keycloak 15.0.2 the embedded runtime, like the Keycloak 15 testsuite server it is based on, reports `X-Forwarded-For` verbatim as the client address even without `proxy-headers`; set the option to get the left-most address, as WildFly reports it.
