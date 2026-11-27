package io.github.wadahiro.keycloak.testframework.quarkus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.realm.UserConfig;
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;
import org.keycloak.testframework.ui.annotations.InjectPage;
import org.keycloak.testframework.ui.annotations.InjectWebDriver;
import org.keycloak.testframework.ui.page.LoginPage;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;

import io.github.wadahiro.keycloak.testframework.proxy.FrontProxy;
import io.github.wadahiro.keycloak.testframework.proxy.InjectFrontProxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KeycloakIntegrationTest(config = FrontProxyIT.ForwardedHeadersServerConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FrontProxyIT {
    private static final String SUBJECT_HEADER = "X-Client-Cert-Subject";

    @InjectRealm
    ManagedRealm realm;

    @InjectUser(config = ProxyUser.class)
    ManagedUser user;

    @InjectFrontProxy
    FrontProxy proxy;

    @InjectOAuthClient(webDriverRef = "front-proxy")
    OAuthClient oauth;

    @InjectWebDriver(ref = "front-proxy", lifecycle = LifeCycle.CLASS)
    ManagedWebDriver browser;

    @InjectPage(webDriverRef = "front-proxy")
    LoginPage loginPage;

    @Test
    @Order(1)
    void configuredHeaderReachesTheProvider() throws Exception {
        proxy.header(SUBJECT_HEADER, "CN=alice,O=Example");

        Map<String, String> seen = providerView(proxy.getRealmUrl(realm.getName()));

        assertEquals("CN=alice,O=Example", seen.get("header"));
        assertEquals(proxy.getBaseUrl() + "/", seen.get("base-uri"), "Keycloak should build URLs for the proxy");
    }

    @Test
    void remoteAddressFollowsXForwardedFor() throws Exception {
        assertEquals("127.0.0.1", providerView(proxy.getRealmUrl(realm.getName())).get("remote-address"));

        proxy.clientAddress("203.0.113.10");

        assertEquals("203.0.113.10", providerView(proxy.getRealmUrl(realm.getName())).get("remote-address"));

        // A chain of proxies: the left-most entry is the original client.
        proxy.clientAddress("198.51.100.7, 203.0.113.10");

        assertEquals("198.51.100.7", providerView(proxy.getRealmUrl(realm.getName())).get("remote-address"));
    }

    @Test
    @Order(2)
    void configuredHeadersAreResetBeforeTheNextTest() throws Exception {
        assertEquals("", providerView(proxy.getRealmUrl(realm.getName())).get("header"));
    }

    @Test
    void browserLoginCompletesThroughTheProxy() throws Exception {
        // The realm's default "SSL required: external requests" would demand HTTPS from a public client address.
        proxy.header(SUBJECT_HEADER, "CN=alice,O=Example").clientAddress("10.0.0.15");
        oauth.baseUrl(proxy.getBaseUrl());

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
        assertEquals(user.getUsername(), oauth.doUserInfoRequest(tokens.getAccessToken()).getUserInfo().getPreferredUsername());

        browser.driver().get(proxy.getRealmUrl(realm.getName()) + "/custom-test-spi/request?header=" + SUBJECT_HEADER);
        String page = browser.driver().getPageSource();
        assertTrue(page.contains("header=CN=alice,O=Example"), page);
        assertTrue(page.contains("remote-address=10.0.0.15"), page);
    }

    static Map<String, String> providerView(String realmUrl) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(realmUrl + "/custom-test-spi/request?header=" + SUBJECT_HEADER)).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Map<String, String> values = new LinkedHashMap<>();
        response.body().lines().forEach(line -> {
            int separator = line.indexOf('=');
            values.put(line.substring(0, separator), line.substring(separator + 1));
        });
        return values;
    }

    public static class ForwardedHeadersServerConfig implements KeycloakServerConfig {
        @Override
        public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder builder) {
            return builder.dependency("io.github.wadahiro.keycloak.testframework", System.getProperty("quarkus.spi.fixture.artifact"))
                    .option("proxy-headers", "xforwarded");
        }
    }

    public static class ProxyUser implements UserConfig {
        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username("proxied-user").password("proxied-password").name("Proxied", "User")
                    .email("proxied-user@example.test");
        }
    }
}
