package io.github.wadahiro.keycloak.testframework.broker;

import java.util.List;

import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;

public final class OidcTestIdentityProviderSupplier implements Supplier<OidcTestIdentityProvider, InjectOidcTestIdentityProvider> {
    @Override
    public OidcTestIdentityProvider getValue(InstanceContext<OidcTestIdentityProvider, InjectOidcTestIdentityProvider> context) {
        return new OidcTestIdentityProvider();
    }

    @Override
    public boolean compatible(InstanceContext<OidcTestIdentityProvider, InjectOidcTestIdentityProvider> a,
            RequestedInstance<OidcTestIdentityProvider, InjectOidcTestIdentityProvider> b) {
        return a.getAnnotation().ref().equals(b.getAnnotation().ref());
    }

    @Override
    public void close(InstanceContext<OidcTestIdentityProvider, InjectOidcTestIdentityProvider> context) {
        context.getValue().close();
    }
}
