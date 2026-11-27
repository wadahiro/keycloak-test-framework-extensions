package io.github.wadahiro.keycloak.testframework.ldap;

import java.util.Arrays;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryDirectoryServerSnapshot;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.Schema;
import io.github.wadahiro.keycloak.testframework.snapshot.SnapshotParticipant;

/** A loopback-only UnboundID LDAP server that joins the snapshot lifecycle. */
public final class LdapTestServer implements AutoCloseable, SnapshotParticipant {
    private final String baseDn;
    private final String bindDn;
    private final String bindPassword;
    private final InMemoryDirectoryServer directory;
    private final MemberOfSynchronizer memberOf;
    private InMemoryDirectoryServerSnapshot baseline;

    LdapTestServer(InjectLdapTestServer config) {
        baseDn = config.baseDn();
        bindDn = config.bindDn();
        bindPassword = config.bindPassword();
        memberOf = config.memberOf()
                ? new MemberOfSynchronizer(Arrays.asList(config.memberOfMembershipAttributes())) : null;
        try {
            InMemoryDirectoryServerConfig serverConfig = new InMemoryDirectoryServerConfig(baseDn);
            serverConfig.addAdditionalBindCredentials(bindDn, bindPassword);
            if (config.schema().length > 0 || memberOf != null) {
                serverConfig.setSchema(schema(serverConfig.getSchema(), config.schema()));
            }
            if (memberOf != null) serverConfig.addInMemoryOperationInterceptor(memberOf);
            directory = new InMemoryDirectoryServer(serverConfig);
            if (memberOf != null) memberOf.attach(directory);
            directory.startListening();
        } catch (LDAPException e) {
            throw new IllegalStateException("Unable to start the in-memory LDAP test server", e);
        }
    }

    private Schema schema(Schema standard, String[] locations) {
        Schema merged = standard;
        if (locations.length > 0) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = LdapTestServer.class.getClassLoader();
            merged = Schema.mergeSchemas(merged, LdapSchemaLoader.load(loader, locations));
        }
        if (memberOf != null && merged.getAttributeType(MemberOfSynchronizer.MEMBER_OF) == null) {
            merged = Schema.mergeSchemas(merged, new Schema(MemberOfSynchronizer.memberOfSchemaEntry()));
        }
        return merged;
    }

    public String getBaseDn() { return baseDn; }

    public String getBindDn() { return bindDn; }

    public String getBindPassword() { return bindPassword; }

    public String getConnectionUrl() { return "ldap://127.0.0.1:" + directory.getListenPort(); }

    /**
     * Returns the LDAP SDK server to seed entries, inspect state, and apply test mutations.
     *
     * <p>Calls on this handle bypass the memberOf emulation; LDAP clients see recomputed values, and
     * {@link #synchronizeMemberOf()} updates them for direct reads.
     */
    public InMemoryDirectoryServer directory() { return directory; }

    /**
     * Recomputes {@code memberOf} on every member entry from the current group membership.
     *
     * @throws IllegalStateException if the server was not created with {@code memberOf = true}
     */
    public void synchronizeMemberOf() {
        if (memberOf == null) {
            throw new IllegalStateException("memberOf emulation is disabled; set @InjectLdapTestServer(memberOf = true)");
        }
        try {
            memberOf.synchronize();
        } catch (LDAPException e) {
            throw new IllegalStateException("Unable to synchronize memberOf", e);
        }
    }

    @Override
    public synchronized void capture() {
        if (memberOf != null) synchronizeMemberOf();
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
