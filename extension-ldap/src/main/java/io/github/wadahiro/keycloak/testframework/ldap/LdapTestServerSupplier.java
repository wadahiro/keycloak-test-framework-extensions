package io.github.wadahiro.keycloak.testframework.ldap;

import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;
import org.keycloak.testframework.injection.SupplierOrder;
import org.keycloak.testframework.injection.Dependency;
import org.keycloak.testframework.injection.DependenciesBuilder;
import io.github.wadahiro.keycloak.testframework.undertow.H2Snapshot;

public final class LdapTestServerSupplier implements Supplier<LdapTestServer, InjectLdapTestServer> {
    @Override
    public java.util.List<Dependency> getDependencies(RequestedInstance<LdapTestServer, InjectLdapTestServer> context) {
        return DependenciesBuilder.create(H2Snapshot.class).build();
    }

    @Override
    public int order() { return SupplierOrder.KEYCLOAK_SERVER + 1; }

    @Override
    public LdapTestServer getValue(InstanceContext<LdapTestServer, InjectLdapTestServer> context) {
        LdapTestServer server = new LdapTestServer(context.getAnnotation());
        context.getDependency(H2Snapshot.class).addParticipant(server);
        return server;
    }

    @Override
    public boolean compatible(InstanceContext<LdapTestServer, InjectLdapTestServer> a,
            RequestedInstance<LdapTestServer, InjectLdapTestServer> b) {
        InjectLdapTestServer x = a.getAnnotation();
        InjectLdapTestServer y = b.getAnnotation();
        return x.baseDn().equals(y.baseDn()) && x.bindDn().equals(y.bindDn())
                && x.bindPassword().equals(y.bindPassword());
    }

    @Override
    public void close(InstanceContext<LdapTestServer, InjectLdapTestServer> context) {
        context.getValue().close();
    }
}
