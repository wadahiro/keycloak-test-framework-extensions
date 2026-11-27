package io.github.wadahiro.keycloak.testframework.proxy;

import java.util.List;

import org.keycloak.testframework.injection.DependenciesBuilder;
import org.keycloak.testframework.injection.Dependency;
import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;
import org.keycloak.testframework.server.KeycloakServer;

public final class FrontProxySupplier implements Supplier<FrontProxy, InjectFrontProxy> {
    @Override
    public List<Dependency> getDependencies(RequestedInstance<FrontProxy, InjectFrontProxy> instance) {
        return DependenciesBuilder.create(KeycloakServer.class).build();
    }

    @Override
    public FrontProxy getValue(InstanceContext<FrontProxy, InjectFrontProxy> context) {
        return new FrontProxy(context.getDependency(KeycloakServer.class).getBaseUrl());
    }

    @Override
    public boolean compatible(InstanceContext<FrontProxy, InjectFrontProxy> a,
            RequestedInstance<FrontProxy, InjectFrontProxy> b) {
        return a.getAnnotation().ref().equals(b.getAnnotation().ref());
    }

    /** Headers and the client address apply per test: each test starts from a plain forwarding proxy. */
    @Override
    public void onBeforeEach(InstanceContext<FrontProxy, InjectFrontProxy> context) {
        context.getValue().reset();
    }

    @Override
    public void close(InstanceContext<FrontProxy, InjectFrontProxy> context) {
        context.getValue().close();
    }
}
