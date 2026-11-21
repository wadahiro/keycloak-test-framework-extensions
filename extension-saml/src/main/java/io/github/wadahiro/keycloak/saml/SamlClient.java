package io.github.wadahiro.keycloak.saml;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.onelogin.saml2.authn.AuthnRequest;
import com.onelogin.saml2.authn.SamlResponse;
import com.onelogin.saml2.settings.Saml2Settings;
import com.onelogin.saml2.settings.SettingsBuilder;
import org.keycloak.testframework.realm.ManagedClient;
import org.keycloak.testframework.ui.page.LoginPage;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;
import org.openqa.selenium.support.PageFactory;

public final class SamlClient implements AutoCloseable {
    private final String keycloakBase;
    private final String realm;
    private final String clientId;
    private final SamlAcs acs;
    private final ManagedWebDriver browser;
    private final ManagedClient managedClient;
    private String idpEntityId;
    private String ssoUrl;
    private String idpCertificate;

    SamlClient(String keycloakBase, String realm, String clientId, SamlAcs acs, ManagedWebDriver browser,
            ManagedClient managedClient) {
        this.keycloakBase = keycloakBase;
        this.realm = realm;
        this.clientId = clientId;
        this.acs = acs;
        this.browser = browser;
        this.managedClient = managedClient;
    }

    public SamlLoginFlow startLogin() {
        ensureMetadata();
        try {
            Saml2Settings settings = settings();
            AuthnRequest request = new AuthnRequest(settings);
            String relayState = UUID.randomUUID().toString();
            CompletableFuture<String> response = acs.register(relayState);
            String destination = ssoUrl + "?SAMLRequest=" + encode(request.getEncodedAuthnRequest())
                    + "&RelayState=" + encode(relayState);
            browser.open(destination);
            return new SamlLoginFlow(request.getId(), relayState, response, settings);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start SAML login for client " + clientId, e);
        }
    }

    public SamlLoginResult doLogin(String username, String password) {
        SamlLoginFlow flow = startLogin();
        LoginPage loginPage = new LoginPage(browser);
        PageFactory.initElements(browser.driver(), loginPage);
        loginPage.assertCurrent();
        loginPage.fillLogin(username, password);
        loginPage.submit();
        return flow.awaitResult(30, TimeUnit.SECONDS);
    }

    public String getClientId() {
        return clientId;
    }

    ManagedClient managedClient() {
        return managedClient;
    }

    private void ensureMetadata() {
        if (idpEntityId != null) return;
        String metadataUrl = keycloakBase + "/realms/" + encodePath(realm) + "/protocol/saml/descriptor";
        try (var stream = java.net.URI.create(metadataUrl).toURL().openStream()) {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var document = factory.newDocumentBuilder().parse(stream);
            var root = document.getDocumentElement();
            idpEntityId = root.getAttribute("entityID");
            var endpoints = document.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:metadata", "SingleSignOnService");
            for (int i = 0; i < endpoints.getLength(); i++) {
                var endpoint = (org.w3c.dom.Element) endpoints.item(i);
                if ("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect".equals(endpoint.getAttribute("Binding"))) {
                    ssoUrl = endpoint.getAttribute("Location");
                    break;
                }
            }
            var certificates = document.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:metadata", "KeyDescriptor");
            for (int i = 0; i < certificates.getLength() && idpCertificate == null; i++) {
                var descriptor = (org.w3c.dom.Element) certificates.item(i);
                String use = descriptor.getAttribute("use");
                if (!use.isEmpty() && !"signing".equals(use)) continue;
                var cert = descriptor.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "X509Certificate");
                if (cert.getLength() > 0) idpCertificate = cert.item(0).getTextContent().replaceAll("\\s", "");
            }
            if (idpEntityId.isBlank() || ssoUrl == null || idpCertificate == null) {
                throw new IllegalStateException("Keycloak SAML metadata is missing entity ID, Redirect SSO, or signing certificate: " + metadataUrl);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read Keycloak SAML metadata from " + metadataUrl, e);
        }
    }

    private Saml2Settings settings() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("onelogin.saml2.strict", true);
        values.put("onelogin.saml2.debug", false);
        values.put("onelogin.saml2.sp.entityid", clientId);
        values.put("onelogin.saml2.sp.assertion_consumer_service.url", acs.getUrl());
        values.put("onelogin.saml2.sp.assertion_consumer_service.binding", "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST");
        values.put("onelogin.saml2.idp.entityid", idpEntityId);
        values.put("onelogin.saml2.idp.single_sign_on_service.url", ssoUrl);
        values.put("onelogin.saml2.idp.single_sign_on_service.binding", "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect");
        values.put("onelogin.saml2.idp.x509cert", "-----BEGIN CERTIFICATE-----\n" + idpCertificate + "\n-----END CERTIFICATE-----");
        values.put("onelogin.saml2.security.want_xml_validation", true);
        return new SettingsBuilder().fromValues(values).build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    @Override
    public void close() {
        acs.close();
    }

    public final class SamlLoginFlow {
        private final String requestId;
        private final String relayState;
        private final CompletableFuture<String> response;
        private final Saml2Settings settings;

        private SamlLoginFlow(String requestId, String relayState, CompletableFuture<String> response,
                Saml2Settings settings) {
            this.requestId = requestId;
            this.relayState = relayState;
            this.response = response;
            this.settings = settings;
        }

        /**
         * Returns the Framework-managed browser used to start this login.
         * Use it to drive custom authenticators, required actions, and other interactive steps.
         */
        public ManagedWebDriver browser() {
            return browser;
        }

        public SamlLoginResult awaitResult(long timeout, TimeUnit unit) {
            String encodedResponse = acs.await(relayState, response, timeout, unit);
            try {
                SamlResponse samlResponse = new SamlResponse(settings, acs.getUrl(), encodedResponse);
                if (!samlResponse.isValid(requestId)) {
                    throw new IllegalStateException("Keycloak returned an invalid SAML response", samlResponse.getValidationException());
                }
                return new SamlLoginResult(samlResponse.getNameId(), samlResponse.getNameIdFormat(),
                        samlResponse.getSessionIndex(), samlResponse.getId(), extractAttributes(encodedResponse));
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to validate Keycloak SAML response", e);
            }
        }
    }

    private static Map<String, List<String>> extractAttributes(String encodedResponse) throws Exception {
        byte[] xml = java.util.Base64.getDecoder().decode(encodedResponse);
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
        var attributes = new LinkedHashMap<String, List<String>>();
        var elements = document.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Attribute");
        for (int i = 0; i < elements.getLength(); i++) {
            var attribute = (org.w3c.dom.Element) elements.item(i);
            List<String> values = attributes.computeIfAbsent(attribute.getAttribute("Name"), ignored -> new ArrayList<>());
            var attributeValues = attribute.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "AttributeValue");
            for (int j = 0; j < attributeValues.getLength(); j++) {
                values.add(attributeValues.item(j).getTextContent());
            }
        }
        return attributes;
    }
}
