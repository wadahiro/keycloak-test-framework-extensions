package io.github.wadahiro.keycloak.testframework.fixture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public final class CustomRealmResourceProvider implements RealmResourceProvider {
    private final KeycloakSession session;

    CustomRealmResourceProvider(KeycloakSession session) {
        this.session = session;
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
        return "custom-quarkus-spi-loaded";
    }

    /** What a provider sees of the request: the client address, one request header and the request's base URI. */
    @GET
    @Path("request")
    @Produces(MediaType.TEXT_PLAIN)
    public String request(@QueryParam("header") String header) {
        var context = session.getContext();
        String value = header == null ? null : context.getRequestHeaders().getHeaderString(header);
        return "remote-address=" + context.getConnection().getRemoteAddr() + "\n"
                + "header=" + (value == null ? "" : value) + "\n"
                + "base-uri=" + context.getUri().getBaseUri() + "\n";
    }
}
