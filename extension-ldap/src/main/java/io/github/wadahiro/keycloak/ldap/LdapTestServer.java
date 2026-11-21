package io.github.wadahiro.keycloak.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryDirectoryServerSnapshot;
import com.unboundid.ldap.sdk.LDAPException;
import io.github.wadahiro.keycloak.undertow.H2SnapshotParticipant;

/** A loopback-only UnboundID LDAP server that joins the Undertow H2 snapshot lifecycle. */
public final class LdapTestServer implements AutoCloseable, H2SnapshotParticipant {
    private final String baseDn;
    private final String bindDn;
    private final String bindPassword;
    private final InMemoryDirectoryServer directory;
    private InMemoryDirectoryServerSnapshot baseline;

    LdapTestServer(InjectLdapTestServer config) {
        baseDn = config.baseDn();
        bindDn = config.bindDn();
        bindPassword = config.bindPassword();
        try {
            InMemoryDirectoryServerConfig serverConfig = new InMemoryDirectoryServerConfig(baseDn);
            serverConfig.addAdditionalBindCredentials(bindDn, bindPassword);
            directory = new InMemoryDirectoryServer(serverConfig);
            directory.startListening();
        } catch (LDAPException e) {
            throw new IllegalStateException("Unable to start the in-memory LDAP test server", e);
        }
    }

    public String getBaseDn() { return baseDn; }

    public String getBindDn() { return bindDn; }

    public String getBindPassword() { return bindPassword; }

    public String getConnectionUrl() { return "ldap://127.0.0.1:" + directory.getListenPort(); }

    /** Returns the LDAP SDK server to seed entries, inspect state, and apply test mutations. */
    public InMemoryDirectoryServer directory() { return directory; }

    @Override
    public synchronized void capture() {
        baseline = directory.createSnapshot();
    }

    @Override
    public synchronized void restore() {
        if (baseline == null) throw new IllegalStateException("No LDAP baseline has been captured");
        directory.restoreSnapshot(baseline);
    }

    @Override
    public void close() {
        directory.shutDown(true);
    }
}
