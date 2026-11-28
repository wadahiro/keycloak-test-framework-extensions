package io.github.wadahiro.keycloak.testframework.fixture15.ear;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import io.github.wadahiro.keycloak.testframework.fixture15.earlib.EarLibrary;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/**
 * Uses the provider id of {@code undertow-spi-fixture}'s realm resource, so a test deploying both can tell which one the
 * server kept; its response comes from the EAR's library JAR.
 */
public final class EarRealmResourceProviderFactory implements RealmResourceProviderFactory {
    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new Resource();
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return "custom-test-spi";
    }

    public static final class Resource implements RealmResourceProvider {
        @Override
        public Object getResource() {
            return this;
        }

        @Override
        public void close() {
        }

        @GET
        @Path("status")
        @Produces(MediaType.TEXT_PLAIN)
        public String status() {
            return "ear-provider-loaded " + EarLibrary.describe();
        }
    }
}
