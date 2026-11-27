package io.github.wadahiro.keycloak.testframework.proxy;

import javax.net.ssl.SSLContext;

import org.htmlunit.HttpWebConnection;
import org.htmlunit.WebClient;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

/**
 * Makes a Framework-managed browser work with a TLS {@link FrontProxy}.
 *
 * <p>The Framework's {@code chrome-headless} browser starts with {@code --ignore-certificate-errors}, so it accepts the
 * proxy's self-signed certificate without configuration, but it cannot present a client certificate. The
 * {@code htmlunit} browser validates certificates against its own TLS settings; these methods replace them through
 * HtmlUnit's public {@code WebClientOptions#setSSLContext}, so it trusts the proxy and can present a client
 * certificate. Other browsers are not supported.
 */
public final class FrontProxyBrowser {
    private FrontProxyBrowser() { }

    /** Makes {@code browser} trust the proxy's certificate; a no-op for {@code chrome-headless}. */
    public static void trust(ManagedWebDriver browser, FrontProxy proxy) {
        WebDriver driver = browser.driver();
        if (driver instanceof HtmlUnitDriver htmlUnit) use(htmlUnit, proxy.sslContext());
    }

    /**
     * Makes {@code browser} trust the proxy and present {@code certificate} when the proxy requests one. Only the
     * {@code htmlunit} browser can present a client certificate; simulate it for other browsers with
     * {@link FrontProxy#header(String, String)}.
     */
    public static void presentClientCertificate(ManagedWebDriver browser, FrontProxy proxy, ClientCertificate certificate) {
        if (!(browser.driver() instanceof HtmlUnitDriver htmlUnit)) {
            throw new UnsupportedOperationException("Only the htmlunit browser can present a client certificate; "
                    + "set the certificate header with FrontProxy.header(...) for " + browser.driver().getClass().getSimpleName());
        }
        use(htmlUnit, proxy.sslContext(certificate));
    }

    private static void use(HtmlUnitDriver driver, SSLContext context) {
        WebClient client = driver.getWebClient();
        client.getOptions().setSSLContext(context);
        // HtmlUnit builds its HTTPS socket factory once per connection pool; closing the pool applies the new context.
        if (client.getWebConnection() instanceof HttpWebConnection connection) connection.close();
    }
}
