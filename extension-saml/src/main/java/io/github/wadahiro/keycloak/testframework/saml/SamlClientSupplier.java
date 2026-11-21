package io.github.wadahiro.keycloak.testframework.saml;

import java.util.List;

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
        SamlAcs acs = new SamlAcs();
        ClientConfig clientConfig = SupplierHelpers.getInstance(annotation.config());
        ClientRepresentation representation = clientConfig.configure(ClientBuilder.create())
                .protocol("saml").publicClient(true).redirectUris(acs.getUrl()).build();
        representation.setStandardFlowEnabled(true);
        representation.getAttributes().put("saml_assertion_consumer_url_post", acs.getUrl());
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
        return new SamlClient(urls.getBase(), realm.getName(), clientId, acs, browser, managedClient);
    }

    @Override
    public boolean compatible(InstanceContext<SamlClient, InjectSamlClient> a,
            RequestedInstance<SamlClient, InjectSamlClient> b) {
        return a.getAnnotation().ref().equals(b.getAnnotation().ref())
                && a.getAnnotation().config().equals(b.getAnnotation().config());
    }

    @Override
    public void close(InstanceContext<SamlClient, InjectSamlClient> context) {
        try {
            context.getValue().close();
            context.getValue().managedClient().admin().remove();
        } finally {
            context.getValue().managedClient().runCleanup();
        }
    }
}
