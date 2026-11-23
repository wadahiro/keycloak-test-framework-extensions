package io.github.wadahiro.keycloak.testframework.quarkus;

import java.io.IOException;
import java.net.URL;
import org.apache.http.client.methods.HttpGet;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.https.CertificatesConfig;
import org.keycloak.testframework.https.CertificatesConfigBuilder;
import org.keycloak.testframework.https.InjectCertificates;
import org.keycloak.testframework.https.ManagedCertificates;
import org.keycloak.testframework.server.KeycloakUrls;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@KeycloakIntegrationTest
class QuarkusTlsIT {
    @InjectCertificates(config = TlsConfig.class) ManagedCertificates certificates;
    @InjectKeycloakUrls KeycloakUrls urls;
    @InjectHttpClient org.apache.http.client.HttpClient httpClient;

    @Test void startsWithFrameworkManagedTlsCertificate() throws IOException {
        URL baseUrl = urls.getBaseUrl();
        assertEquals("https", baseUrl.getProtocol());
        var response = httpClient.execute(new HttpGet(baseUrl.toString()));
        assertEquals(200, response.getStatusLine().getStatusCode());
        org.apache.http.util.EntityUtils.consume(response.getEntity());
    }

    public static class TlsConfig implements CertificatesConfig {
        @Override public CertificatesConfigBuilder configure(CertificatesConfigBuilder builder) {
            return builder.tlsEnabled(true);
        }
    }
}
