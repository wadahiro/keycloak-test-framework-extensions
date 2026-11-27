package io.github.wadahiro.keycloak.testframework.proxy;

import java.util.List;

import org.keycloak.testframework.TestFrameworkExtension;
import org.keycloak.testframework.injection.Supplier;

public final class FrontProxyTestFrameworkExtension implements TestFrameworkExtension {
    @Override
    public List<Supplier<?, ?>> suppliers() {
        return List.of(new FrontProxySupplier());
    }
}
