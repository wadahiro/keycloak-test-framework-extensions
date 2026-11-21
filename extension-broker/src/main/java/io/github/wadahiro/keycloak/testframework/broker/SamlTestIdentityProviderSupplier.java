package io.github.wadahiro.keycloak.testframework.broker;

import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;

public final class SamlTestIdentityProviderSupplier implements Supplier<SamlTestIdentityProvider, InjectSamlTestIdentityProvider> {
    @Override
    public SamlTestIdentityProvider getValue(InstanceContext<SamlTestIdentityProvider, InjectSamlTestIdentityProvider> context) {
        return new SamlTestIdentityProvider();
    }

    @Override
    public boolean compatible(InstanceContext<SamlTestIdentityProvider, InjectSamlTestIdentityProvider> a,
            RequestedInstance<SamlTestIdentityProvider, InjectSamlTestIdentityProvider> b) {
        return a.getAnnotation().ref().equals(b.getAnnotation().ref());
    }

    @Override
    public void close(InstanceContext<SamlTestIdentityProvider, InjectSamlTestIdentityProvider> context) {
        context.getValue().close();
    }
}
