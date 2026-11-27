package io.github.wadahiro.keycloak.testframework.fixture15;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public final class CustomRealmResourceProvider implements RealmResourceProvider {
    private final KeycloakSession session;
    private final String greeting;

    CustomRealmResourceProvider(KeycloakSession session, String greeting) {
        this.session = session;
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
