package io.github.wadahiro.keycloak.testframework.saml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.testframework.injection.DependenciesBuilder;
import org.keycloak.testframework.injection.Dependency;
import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;
import org.keycloak.testframework.injection.SupplierHelpers;
import org.keycloak.testframework.realm.ClientBuilder;
import org.keycloak.testframework.realm.ClientConfig;
import org.keycloak.testframework.realm.ManagedClient;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.server.KeycloakUrls;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;
import org.keycloak.testframework.util.ApiUtil;

public final class SamlClientSupplier implements Supplier<SamlClient, InjectSamlClient> {
    static final String ACS_POST_ATTRIBUTE = "saml_assertion_consumer_url_post";

    @Override
    public List<Dependency> getDependencies(RequestedInstance<SamlClient, InjectSamlClient> context) {
        InjectSamlClient annotation = context.getAnnotation();
        return DependenciesBuilder.create(KeycloakUrls.class)
                .add(ManagedRealm.class, annotation.realmRef())
                .add(ManagedWebDriver.class, annotation.webDriverRef()).build();
    }

    @Override
    public SamlClient getValue(InstanceContext<SamlClient, InjectSamlClient> context) {
        InjectSamlClient annotation = context.getAnnotation();
        ManagedRealm realm = context.getDependency(ManagedRealm.class, annotation.realmRef());
        KeycloakUrls urls = context.getDependency(KeycloakUrls.class);
        ManagedWebDriver browser = context.getDependency(ManagedWebDriver.class, annotation.webDriverRef());
        if (!annotation.clientId().isBlank()) {
            return existingClient(annotation, realm, urls, browser);
        }
        SamlAcs acs = new SamlAcs();
        ClientConfig clientConfig = SupplierHelpers.getInstance(annotation.config());
        ClientRepresentation representation = clientConfig.configure(ClientBuilder.create())
                .protocol("saml").publicClient(true).redirectUris(acs.getUrl()).build();
        representation.setStandardFlowEnabled(true);
        representation.getAttributes().put(ACS_POST_ATTRIBUTE, acs.getUrl());
        String clientId = representation.getClientId();
        if (!annotation.ref().isBlank()) {
            clientId += "-" + annotation.ref();
            representation.setClientId(clientId);
        }
        Response response = realm.admin().clients().create(representation);
        String id = ApiUtil.getCreatedId(response);
        representation.setId(id);
        ClientResource clientResource = realm.admin().clients().get(id);
        ManagedClient managedClient = new ManagedClient(representation, clientResource);
        return new SamlClient(urls.getBase(), realm.getName(), clientId, representation.getAttributes(), acs,
                browser, managedClient, clientResource::remove);
    }

    private static SamlClient existingClient(InjectSamlClient annotation, ManagedRealm realm, KeycloakUrls urls,
            ManagedWebDriver browser) {
        if (!annotation.config().equals(DefaultSamlClientConfiguration.class)) {
            throw new IllegalArgumentException("@InjectSamlClient config cannot be combined with clientId "
                    + annotation.clientId() + "; configure the existing client in the realm instead");
        }
        String clientId = annotation.clientId();
        List<ClientRepresentation> matches = realm.admin().clients().findByClientId(clientId);
        if (matches.isEmpty()) {
            throw new IllegalStateException("SAML client " + clientId + " does not exist in realm " + realm.getName());
        }
        ClientResource clientResource = realm.admin().clients().get(matches.get(0).getId());
        ClientRepresentation original = clientResource.toRepresentation();
        if (!"saml".equals(original.getProtocol())) {
            throw new IllegalStateException("Client " + clientId + " in realm " + realm.getName()
                    + " is not a SAML client: " + original.getProtocol());
        }
        Map<String, String> originalAttributes = original.getAttributes() == null
                ? Map.of() : new HashMap<>(original.getAttributes());
        List<String> originalRedirectUris = original.getRedirectUris() == null
                ? List.of() : List.copyOf(original.getRedirectUris());

        SamlAcs acs = new SamlAcs();
        List<String> redirectUris = new ArrayList<>(originalRedirectUris);
        redirectUris.add(acs.getUrl());
        try {
            clientResource.update(partialUpdate(original, redirectUris, acs.getUrl()));
        } catch (RuntimeException e) {
            acs.close();
            throw new IllegalStateException("Unable to point SAML client " + clientId + " at the test ACS", e);
        }
        Runnable restore = () -> clientResource.update(partialUpdate(original, originalRedirectUris,
                originalAttributes.getOrDefault(ACS_POST_ATTRIBUTE, "")));
        ManagedClient managedClient = new ManagedClient(original, clientResource);
        return new SamlClient(urls.getBase(), realm.getName(), clientId, originalAttributes, acs, browser,
                managedClient, restore);
    }

    /**
     * Builds an update that changes only the redirect URIs and the POST assertion consumer URL.
     * Keycloak keeps fields that are absent from an update and removes an attribute sent with an empty value.
     */
    private static ClientRepresentation partialUpdate(ClientRepresentation original, List<String> redirectUris,
            String acsPostUrl) {
        ClientRepresentation update = new ClientRepresentation();
        update.setId(original.getId());
        update.setClientId(original.getClientId());
        update.setProtocol(original.getProtocol());
        update.setRedirectUris(new ArrayList<>(redirectUris));
        Map<String, String> attributes = new HashMap<>();
        attributes.put(ACS_POST_ATTRIBUTE, acsPostUrl);
        update.setAttributes(attributes);
        return update;
    }

    @Override
    public boolean compatible(InstanceContext<SamlClient, InjectSamlClient> a,
            RequestedInstance<SamlClient, InjectSamlClient> b) {
        return a.getAnnotation().ref().equals(b.getAnnotation().ref())
                && a.getAnnotation().clientId().equals(b.getAnnotation().clientId())
                && a.getAnnotation().config().equals(b.getAnnotation().config());
    }

    @Override
    public void close(InstanceContext<SamlClient, InjectSamlClient> context) {
        try {
            context.getValue().close();
        } finally {
            context.getValue().managedClient().runCleanup();
        }
    }
}
