package io.github.wadahiro.keycloak.saml;

import org.keycloak.testframework.realm.ClientBuilder;
import org.keycloak.testframework.realm.ClientConfig;

public class DefaultSamlClientConfiguration implements ClientConfig {
    @Override
    public ClientBuilder configure(ClientBuilder client) {
        return client.clientId("saml-test-client")
                .protocol("saml")
                .publicClient(true)
                .attribute("saml.client.signature", "false")
                .attribute("saml.server.signature", "true")
                .attribute("saml.assertion.signature", "true");
    }
}
