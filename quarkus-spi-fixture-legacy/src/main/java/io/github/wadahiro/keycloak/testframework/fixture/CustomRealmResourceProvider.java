package io.github.wadahiro.keycloak.testframework.fixture;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.keycloak.services.resource.RealmResourceProvider;

public final class CustomRealmResourceProvider implements RealmResourceProvider {
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
        return "custom-quarkus-spi-loaded";
    }
}
