package io.github.wadahiro.keycloak.testframework.quarkus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.Test;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.representations.AccessToken;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.InjectUser;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.oauth.OAuthClient;
import org.keycloak.testframework.oauth.annotations.InjectOAuthClient;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.ManagedUser;
import org.keycloak.testframework.ui.annotations.InjectPage;
import org.keycloak.testframework.ui.annotations.InjectWebDriver;
import org.keycloak.testframework.ui.page.LoginPage;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

import io.github.wadahiro.keycloak.testframework.proxy.ClientCertificate;
import io.github.wadahiro.keycloak.testframework.proxy.FrontProxy;
import io.github.wadahiro.keycloak.testframework.proxy.FrontProxyBrowser;
import io.github.wadahiro.keycloak.testframework.proxy.InjectFrontProxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@KeycloakIntegrationTest(config = FrontProxyIT.ForwardedHeadersServerConfig.class)
class FrontProxyTlsIT {
    private static final String SUBJECT_HEADER = "X-Client-Cert-Subject";
    private static final String CERTIFICATE_HEADER = "X-Client-Cert";

    @InjectRealm
    ManagedRealm realm;

    @InjectUser(config = FrontProxyIT.ProxyUser.class)
    ManagedUser user;

    @InjectFrontProxy(ref = "tls", tls = true)
    FrontProxy proxy;

    @InjectFrontProxy(ref = "mtls", tls = true, clientAuth = FrontProxy.ClientAuth.WANT,
            clientCertificateSubjectHeader = SUBJECT_HEADER, clientCertificateHeader = CERTIFICATE_HEADER)
    FrontProxy mtls;

    @InjectFrontProxy(ref = "mtls-required", tls = true, clientAuth = FrontProxy.ClientAuth.NEED,
            clientCertificateSubjectHeader = SUBJECT_HEADER)
    FrontProxy requiredMtls;

    @InjectOAuthClient(webDriverRef = "front-proxy-tls")
    OAuthClient oauth;

    @InjectWebDriver(ref = "front-proxy-tls", lifecycle = LifeCycle.CLASS)
    ManagedWebDriver browser;

    @InjectPage(webDriverRef = "front-proxy-tls")
    LoginPage loginPage;

    @Test
    void keycloakBuildsHttpsUrlsForTheProxy() throws Exception {
        try (var client = HttpClient.newBuilder().sslContext(proxy.sslContext()).build()) {
            assertEquals(proxy.getBaseUrl() + "/", providerView(client, proxy, SUBJECT_HEADER).get("base-uri"));
        }
        assertTrue(proxy.getBaseUrl().startsWith("https://127.0.0.1:"), proxy.getBaseUrl());
    }

    @Test
    void browserLoginFromAPublicAddressCompletesOverHttps() throws Exception {
        // Over HTTPS the realm's default "SSL required: external requests" accepts a public client address.
        proxy.clientAddress("203.0.113.10");
        FrontProxyBrowser.trust(browser, proxy);
        oauth.baseUrl(proxy.getBaseUrl());
        try (var backChannel = HttpClients.custom().setSSLContext(proxy.sslContext()).build()) {
            oauth.httpClient().set(backChannel);

            oauth.openLoginForm();
            BrowserPageAssertions.waitForLoginForm(browser);
            assertTrue(browser.getCurrentUrl().startsWith(proxy.getRealmUrl(realm.getName()) + "/"), browser.getCurrentUrl());
            loginPage.fillLogin(user.getUsername(), user.getPassword());
            loginPage.submit();

            var authorization = oauth.parseLoginResponse();
            assertTrue(authorization.isRedirected(), "Current URL: " + browser.getCurrentUrl() + "\n"
                    + browser.driver().getPageSource());
            var tokens = oauth.doAccessTokenRequest(authorization.getCode());
            assertTrue(tokens.isSuccess(), tokens.getErrorDescription());
            AccessToken token = new JWSInput(tokens.getAccessToken()).readJsonContent(AccessToken.class);
            assertEquals(proxy.getRealmUrl(realm.getName()), token.getIssuer());

            browser.driver().get(proxy.getRealmUrl(realm.getName()) + "/custom-test-spi/request");
            assertTrue(browser.driver().getPageSource().contains("remote-address=203.0.113.10"), browser.driver().getPageSource());
        } finally {
            oauth.httpClient().reset();
        }
    }

    @Test
    void presentedClientCertificateIsForwardedToTheProvider() throws Exception {
        var certificate = ClientCertificate.generate("CN=alice,O=Example");
        try (var client = HttpClient.newBuilder().sslContext(mtls.sslContext(certificate)).build()) {
            assertEquals("CN=alice,O=Example", providerView(client, mtls, SUBJECT_HEADER).get("header"));
            assertTrue(providerView(client, mtls, CERTIFICATE_HEADER).get("header").startsWith("-----BEGIN%20CERTIFICATE-----%0A"));
        }
    }

    @Test
    void certificateHeadersCannotBeSuppliedByTheClient() throws Exception {
        try (var anonymous = HttpClient.newBuilder().sslContext(mtls.sslContext()).build()) {
            var request = HttpRequest.newBuilder(URI.create(mtls.getRealmUrl(realm.getName())
                    + "/custom-test-spi/request?header=" + SUBJECT_HEADER)).header(SUBJECT_HEADER, "CN=forged").GET().build();
            assertTrue(anonymous.send(request, HttpResponse.BodyHandlers.ofString()).body().contains("header=\n"));

            // A browser that cannot present a certificate: the test sets the header the proxy would forward.
            mtls.header(SUBJECT_HEADER, "CN=bob,O=Example");
            assertEquals("CN=bob,O=Example", providerView(anonymous, mtls, SUBJECT_HEADER).get("header"));
        }
    }

    @Test
    void requiredClientCertificateRejectsAnonymousClients() throws Exception {
        try (var anonymous = HttpClient.newBuilder().sslContext(requiredMtls.sslContext()).build()) {
            assertThrows(IOException.class, () -> providerView(anonymous, requiredMtls, SUBJECT_HEADER));
        }
        try (var client = HttpClient.newBuilder().sslContext(requiredMtls.sslContext(ClientCertificate.generate("CN=carol"))).build()) {
            assertEquals("CN=carol", providerView(client, requiredMtls, SUBJECT_HEADER).get("header"));
        }
    }

    @Test
    void htmlUnitPresentsAClientCertificate() {
        assumeTrue(browser.driver() instanceof HtmlUnitDriver, "Only the htmlunit browser can present a client certificate");
        FrontProxyBrowser.presentClientCertificate(browser, mtls, ClientCertificate.generate("CN=dave,O=Example"));

        browser.driver().get(mtls.getRealmUrl(realm.getName()) + "/custom-test-spi/request?header=" + SUBJECT_HEADER);

        assertTrue(browser.driver().getPageSource().contains("header=CN=dave,O=Example"), browser.driver().getPageSource());
    }

    private Map<String, String> providerView(HttpClient client, FrontProxy via, String header) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(via.getRealmUrl(realm.getName()) + "/custom-test-spi/request?header=" + header))
                .GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Map<String, String> values = new LinkedHashMap<>();
        response.body().lines().forEach(line -> {
            int separator = line.indexOf('=');
            values.put(line.substring(0, separator), line.substring(separator + 1));
        });
        return values;
    }
}
