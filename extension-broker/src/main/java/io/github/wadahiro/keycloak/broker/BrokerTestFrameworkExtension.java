package io.github.wadahiro.keycloak.broker;

import java.util.List;

import org.keycloak.testframework.TestFrameworkExtension;
import org.keycloak.testframework.injection.Supplier;

public final class BrokerTestFrameworkExtension implements TestFrameworkExtension {
    @Override
    public List<Supplier<?, ?>> suppliers() {
        return List.of(new OidcTestIdentityProviderSupplier(), new SamlTestIdentityProviderSupplier());
    }
}
