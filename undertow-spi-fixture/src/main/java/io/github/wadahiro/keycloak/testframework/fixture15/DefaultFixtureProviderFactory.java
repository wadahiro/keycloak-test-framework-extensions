package io.github.wadahiro.keycloak.testframework.fixture15;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public final class DefaultFixtureProviderFactory implements FixtureSpi.FixtureProviderFactory {
    @Override
    public FixtureSpi.FixtureProvider create(KeycloakSession session) {
        return new FixtureSpi.FixtureProvider() {
            @Override
            public String describe() {
                return "custom-spi-provider-loaded";
            }

            @Override
            public void close() {
            }
        };
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
        return "default";
    }
}
