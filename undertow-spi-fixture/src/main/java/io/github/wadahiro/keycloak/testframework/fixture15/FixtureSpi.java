package io.github.wadahiro.keycloak.testframework.fixture15;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/** A custom SPI defined by the provider dependency itself. */
public final class FixtureSpi implements Spi {
    public interface FixtureProvider extends Provider {
        String describe();
    }

    public interface FixtureProviderFactory extends ProviderFactory<FixtureProvider> {
    }

    @Override
    public boolean isInternal() {
        return false;
    }

    @Override
    public String getName() {
        return "kctf-fixture";
    }

    @Override
    public Class<? extends Provider> getProviderClass() {
        return FixtureProvider.class;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Class<? extends ProviderFactory> getProviderFactoryClass() {
        return FixtureProviderFactory.class;
    }
}
