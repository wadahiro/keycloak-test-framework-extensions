package io.github.wadahiro.keycloak.testframework.fixture15;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.keycloak.services.resource.RealmResourceProvider;

public final class CustomRealmResourceProvider implements RealmResourceProvider {
    private final String greeting;

    CustomRealmResourceProvider(String greeting) {
        this.greeting = greeting;
    }

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
        return "custom-undertow-spi-loaded";
    }

    @GET
    @Path("config")
    @Produces(MediaType.TEXT_PLAIN)
    public String config() {
        return greeting;
    }
}
