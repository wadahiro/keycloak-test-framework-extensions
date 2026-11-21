package io.github.wadahiro.keycloak.testframework.broker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A local SAML IdP for deterministic Keycloak broker tests. */
public final class SamlTestIdentityProvider implements AutoCloseable {
    private static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String SAMLP = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String MD = "urn:oasis:names:tc:SAML:2.0:metadata";
    private static final String DS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String BINDING_REDIRECT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect";
    private static final String BINDING_POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

    private final HttpServer server;
    private final String entityId;
    private final String ssoUrl;
    private final String metadataUrl;
    private final KeyPair keys;
    private final java.security.cert.X509Certificate certificate;
    private final AtomicReference<SamlIdentity> nextIdentity = new AtomicReference<>(SamlIdentity.nameId("external-user"));
    private final AtomicReference<Boolean> denyNext = new AtomicReference<>(false);

    SamlTestIdentityProvider() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keys = generator.generateKeyPair();
            certificate = createCertificate(keys);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            entityId = base + "/saml/idp";
            ssoUrl = base + "/saml/sso";
            metadataUrl = base + "/saml/metadata";
            server.createContext("/saml/metadata", this::metadata);
            server.createContext("/saml/sso", this::sso);
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to start local SAML test identity provider", e);
        }
    }

    public String getEntityId() { return entityId; }
    public String getSsoUrl() { return ssoUrl; }
    public String getMetadataUrl() { return metadataUrl; }
    public String getSigningCertificate() {
        return Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(encoded(certificate));
    }

    public Map<String, String> keycloakConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("idpEntityId", entityId);
        config.put("singleSignOnServiceUrl", ssoUrl);
        config.put("validateSignature", "true");
        config.put("signingCertificate", getSigningCertificate());
        config.put("postBindingResponse", "true");
        config.put("postBindingAuthnRequest", "false");
        config.put("wantAuthnRequestsSigned", "false");
        return Map.copyOf(config);
    }

    public void nextLogin(SamlIdentity identity) {
        nextIdentity.set(identity);
        denyNext.set(false);
    }

    public void denyNextLogin() { denyNext.set(true); }

    private void metadata(HttpExchange exchange) throws IOException {
        String cert = Base64.getEncoder().encodeToString(encoded(certificate));
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <md:EntityDescriptor xmlns:md="%s" xmlns:ds="%s" entityID="%s">
                  <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol" WantAuthnRequestsSigned="false">
                    <md:KeyDescriptor use="signing"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>
                    <md:SingleSignOnService Binding="%s" Location="%s"/>
                    <md:SingleSignOnService Binding="%s" Location="%s"/>
                  </md:IDPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(MD, DS, entityId, cert, BINDING_REDIRECT, ssoUrl, BINDING_POST, ssoUrl);
        xml(exchange, 200, xml);
    }

    private void sso(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> parameters;
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                parameters = form(exchange.getRequestURI().getRawQuery());
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                parameters = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } else {
                respond(exchange, 405, "GET or POST required");
                return;
            }
            String encodedRequest = parameters.get("SAMLRequest");
            String relayState = parameters.get("RelayState");
            if (encodedRequest == null) throw new IllegalArgumentException("Missing SAMLRequest");
            String requestXml = decodeRequest(encodedRequest, "GET".equalsIgnoreCase(exchange.getRequestMethod()));
            Document request = parseXml(requestXml);
            Element root = request.getDocumentElement();
            String requestId = required(root.getAttribute("ID"), "AuthnRequest ID");
            String acs = required(root.getAttribute("AssertionConsumerServiceURL"), "AssertionConsumerServiceURL");
            Element issuerElement = (Element) request.getElementsByTagNameNS(SAML, "Issuer").item(0);
            String audience = issuerElement == null ? null : issuerElement.getTextContent();
            String response = denyNext.getAndSet(false)
                    ? createErrorResponse(requestId, acs)
                    : createResponse(requestId, acs, audience, nextIdentity.getAndSet(null));
            String encodedResponse = Base64.getEncoder().encodeToString(response.getBytes(StandardCharsets.UTF_8));
            String page = """
                    <!doctype html><html><body><form id="saml-response" method="post" action="%s">
                    <input type="hidden" name="SAMLResponse" value="%s"><input type="hidden" name="RelayState" value="%s">
                    </form><script>document.getElementById('saml-response').submit()</script></body></html>
                    """.formatted(html(acs), html(encodedResponse), html(relayState == null ? "" : relayState));
            html(exchange, 200, page);
        } catch (Exception e) {
            respond(exchange, 400, "Invalid SAML AuthnRequest: " + e.getMessage());
        }
    }

    private String createResponse(String requestId, String acs, String audience, SamlIdentity requestedIdentity) throws Exception {
        SamlIdentity identity = requestedIdentity == null ? SamlIdentity.nameId("external-user") : requestedIdentity;
        Instant now = Instant.now();
        String issueInstant = now.toString();
        String responseId = "_" + UUID.randomUUID();
        String assertionId = "_" + UUID.randomUUID();
        Document doc = newDocument();
        Element response = doc.createElementNS(SAMLP, "samlp:Response");
        response.setAttribute("xmlns:samlp", SAMLP);
        response.setAttribute("xmlns:saml", SAML);
        response.setAttribute("ID", responseId);
        response.setAttribute("Version", "2.0");
        response.setAttribute("IssueInstant", issueInstant);
        response.setAttribute("Destination", acs);
        response.setAttribute("InResponseTo", requestId);
        doc.appendChild(response);
        appendText(doc, response, SAML, "saml:Issuer", entityId);
        Element status = doc.createElementNS(SAMLP, "samlp:Status");
        Element statusCode = doc.createElementNS(SAMLP, "samlp:StatusCode");
        statusCode.setAttribute("Value", "urn:oasis:names:tc:SAML:2.0:status:Success");
        status.appendChild(statusCode);
        response.appendChild(status);

        Element assertion = doc.createElementNS(SAML, "saml:Assertion");
        assertion.setAttribute("ID", assertionId);
        assertion.setIdAttribute("ID", true);
        assertion.setAttribute("Version", "2.0");
        assertion.setAttribute("IssueInstant", issueInstant);
        response.appendChild(assertion);
        appendText(doc, assertion, SAML, "saml:Issuer", entityId);
        Element subject = doc.createElementNS(SAML, "saml:Subject");
        Element nameId = appendText(doc, subject, SAML, "saml:NameID", identity.nameId());
        nameId.setAttribute("Format", identity.nameIdFormat());
        Element confirmation = doc.createElementNS(SAML, "saml:SubjectConfirmation");
        confirmation.setAttribute("Method", "urn:oasis:names:tc:SAML:2.0:cm:bearer");
        Element confirmationData = doc.createElementNS(SAML, "saml:SubjectConfirmationData");
        confirmationData.setAttribute("InResponseTo", requestId);
        confirmationData.setAttribute("Recipient", acs);
        confirmationData.setAttribute("NotOnOrAfter", now.plus(5, ChronoUnit.MINUTES).toString());
        confirmation.appendChild(confirmationData);
        subject.appendChild(confirmation);
        assertion.appendChild(subject);

        Element conditions = doc.createElementNS(SAML, "saml:Conditions");
        conditions.setAttribute("NotBefore", now.minus(30, ChronoUnit.SECONDS).toString());
        conditions.setAttribute("NotOnOrAfter", now.plus(5, ChronoUnit.MINUTES).toString());
        if (audience != null && !audience.isBlank()) {
            Element restriction = doc.createElementNS(SAML, "saml:AudienceRestriction");
            appendText(doc, restriction, SAML, "saml:Audience", audience);
            conditions.appendChild(restriction);
        }
        assertion.appendChild(conditions);

        Element authnStatement = doc.createElementNS(SAML, "saml:AuthnStatement");
        authnStatement.setAttribute("AuthnInstant", issueInstant);
        authnStatement.setAttribute("SessionIndex", "_" + UUID.randomUUID());
        Element authnContext = doc.createElementNS(SAML, "saml:AuthnContext");
        appendText(doc, authnContext, SAML, "saml:AuthnContextClassRef",
                "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport");
        authnStatement.appendChild(authnContext);
        assertion.appendChild(authnStatement);

        if (!identity.attributes().isEmpty()) {
            Element statement = doc.createElementNS(SAML, "saml:AttributeStatement");
            identity.attributes().forEach((attributeName, values) -> {
                Element attribute = doc.createElementNS(SAML, "saml:Attribute");
                attribute.setAttribute("Name", attributeName);
                values.forEach(value -> appendText(doc, attribute, SAML, "saml:AttributeValue", value));
                statement.appendChild(attribute);
            });
            assertion.appendChild(statement);
        }
        Document normalized = parseXml(serialize(doc));
        Element normalizedResponse = normalized.getDocumentElement();
        Element normalizedStatus = (Element) normalizedResponse.getElementsByTagNameNS(SAMLP, "Status").item(0);
        signResponse(normalized, normalizedResponse, normalizedStatus);
        return serialize(normalized);
    }

    private String createErrorResponse(String requestId, String acs) throws Exception {
        Document doc = newDocument();
        Element response = doc.createElementNS(SAMLP, "samlp:Response");
        response.setAttribute("xmlns:samlp", SAMLP);
        response.setAttribute("xmlns:saml", SAML);
        response.setAttribute("ID", "_" + UUID.randomUUID());
        response.setAttribute("Version", "2.0");
        response.setAttribute("IssueInstant", Instant.now().toString());
        response.setAttribute("Destination", acs);
        response.setAttribute("InResponseTo", requestId);
        doc.appendChild(response);
        appendText(doc, response, SAML, "saml:Issuer", entityId);
        Element status = doc.createElementNS(SAMLP, "samlp:Status");
        Element statusCode = doc.createElementNS(SAMLP, "samlp:StatusCode");
        statusCode.setAttribute("Value", "urn:oasis:names:tc:SAML:2.0:status:Responder");
        Element nestedCode = doc.createElementNS(SAMLP, "samlp:StatusCode");
        nestedCode.setAttribute("Value", "urn:oasis:names:tc:SAML:2.0:status:AuthnFailed");
        statusCode.appendChild(nestedCode);
        status.appendChild(statusCode);
        response.appendChild(status);
        Document normalized = parseXml(serialize(doc));
        Element normalizedResponse = normalized.getDocumentElement();
        Element normalizedStatus = (Element) normalizedResponse.getElementsByTagNameNS(SAMLP, "Status").item(0);
        signResponse(normalized, normalizedResponse, normalizedStatus);
        return serialize(normalized);
    }

    private void signResponse(Document document, Element response, Element status) throws Exception {
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        String responseId = response.getAttribute("ID");
        response.setIdAttribute("ID", true);
        Reference reference = factory.newReference("#" + responseId,
                factory.newDigestMethod(DigestMethod.SHA256, null),
                List.of(factory.newTransform(Transform.ENVELOPED,
                                (javax.xml.crypto.dsig.spec.TransformParameterSpec) null),
                        factory.newTransform(CanonicalizationMethod.EXCLUSIVE,
                                (javax.xml.crypto.dsig.spec.TransformParameterSpec) null)),
                null, null);
        var signedInfo = factory.newSignedInfo(factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                        (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null),
                factory.newSignatureMethod("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", null),
                List.of(reference));
        KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
        var keyInfo = keyInfoFactory.newKeyInfo(List.of(keyInfoFactory.newX509Data(List.of(certificate))));
        DOMSignContext context = new DOMSignContext(keys.getPrivate(), response, status);
        context.setDefaultNamespacePrefix("ds");
        factory.newXMLSignature(signedInfo, keyInfo).sign(context);
    }

    private static String decodeRequest(String encoded, boolean redirectBinding) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (!redirectBinding) return new String(bytes, StandardCharsets.UTF_8);
        try (var inflater = new InflaterInputStream(new ByteArrayInputStream(bytes), new Inflater(true));
                var output = new ByteArrayOutputStream()) {
            inflater.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> result = new HashMap<>();
        for (String pair : encoded.split("&")) {
            String[] keyValue = pair.split("=", 2);
            result.put(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8),
                    keyValue.length == 2 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Document newDocument() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().newDocument();
    }

    private static Element appendText(Document doc, Element parent, String namespace, String name, String value) {
        Element element = doc.createElementNS(namespace, name);
        element.setTextContent(value);
        parent.appendChild(element);
        return element;
    }

    private static String serialize(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private static java.security.cert.X509Certificate createCertificate(KeyPair keys) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=Keycloak Test Broker IdP");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                new BigInteger(128, new java.security.SecureRandom()), java.util.Date.from(now.minus(1, ChronoUnit.DAYS)),
                java.util.Date.from(now.plus(365, ChronoUnit.DAYS)), name, keys.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static byte[] encoded(java.security.cert.X509Certificate certificate) {
        try { return certificate.getEncoded(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + label);
        return value;
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void xml(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/samlmetadata+xml; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void html(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @Override public void close() { server.stop(0); }
}
