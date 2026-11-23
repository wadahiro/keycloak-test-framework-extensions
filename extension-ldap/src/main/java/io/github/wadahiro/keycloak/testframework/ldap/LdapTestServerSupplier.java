package io.github.wadahiro.keycloak.testframework.ldap;

import java.util.List;
import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;
import org.keycloak.testframework.injection.SupplierOrder;

public final class LdapTestServerSupplier implements Supplier<LdapTestServer, InjectLdapTestServer> {
    @Override
    public int order() { return SupplierOrder.KEYCLOAK_SERVER + 1; }

    @Override
    public LdapTestServer getValue(InstanceContext<LdapTestServer, InjectLdapTestServer> context) {
        return new LdapTestServer(context.getAnnotation());
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
