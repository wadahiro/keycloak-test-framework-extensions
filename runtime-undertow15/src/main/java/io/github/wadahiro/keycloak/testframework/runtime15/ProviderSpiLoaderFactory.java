package io.github.wadahiro.keycloak.testframework.runtime15;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.keycloak.provider.KeycloakDeploymentInfo;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.ProviderLoader;
import org.keycloak.provider.ProviderLoaderFactory;
import org.keycloak.provider.Spi;

/**
 * Adds the SPI definitions ({@code org.keycloak.provider.Spi}) of provider dependencies to Keycloak's built-in provider
 * manager, which is the only place Keycloak 15 reads SPI definitions from. Their factories come from
 * {@link ProviderDeployment}, so this loader returns none.
 */
public final class ProviderSpiLoaderFactory implements ProviderLoaderFactory {
    static final String TYPE = "kctf-provider-spis";

    @Override public boolean supports(String type) { return TYPE.equals(type); }

    @Override public ProviderLoader create(KeycloakDeploymentInfo info, ClassLoader baseClassLoader, String resource) {
        return new ProviderLoader() {
            @Override public List<Spi> loadSpis() {
                ClassLoader loader = ProviderDeployment.loader();
                List<Spi> spis = new ArrayList<>();
                if (loader != null) ServiceLoader.load(Spi.class, loader).forEach(spis::add);
                return spis;
            }

            @Override public List<ProviderFactory> load(Spi spi) { return List.of(); }
        };
    }
}
