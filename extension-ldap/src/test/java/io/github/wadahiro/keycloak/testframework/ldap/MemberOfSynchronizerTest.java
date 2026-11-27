package io.github.wadahiro.keycloak.testframework.ldap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MemberOfSynchronizerTest {
    private static final String BASE = "dc=example,dc=test";
    private static final String ALICE = "uid=alice,ou=people," + BASE;
    private static final String BOB = "uid=bob,ou=people," + BASE;
    private static final String DEVELOPERS = "cn=developers,ou=groups," + BASE;
    private static final String OPERATORS = "cn=operators,ou=groups," + BASE;

    private LdapTestServer server;
    private LDAPConnection client;

    @AfterEach
    void close() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    @Test
    void clientWritesKeepMemberOfInSync() throws Exception {
        start("memberOf");
        client.add(group(DEVELOPERS, "member", ALICE, BOB));
        assertMemberOf(ALICE, DEVELOPERS);
        assertMemberOf(BOB, DEVELOPERS);

        client.add(group(OPERATORS, "member", ALICE));
        assertMemberOf(ALICE, DEVELOPERS, OPERATORS);

        client.modify(DEVELOPERS, new Modification(ModificationType.DELETE, "member", BOB));
        assertMemberOf(BOB);
        client.modify(OPERATORS, new Modification(ModificationType.ADD, "member", BOB));
        assertMemberOf(BOB, OPERATORS);
        client.modify(OPERATORS, new Modification(ModificationType.REPLACE, "member", BOB));
        assertMemberOf(ALICE, DEVELOPERS);
        assertMemberOf(BOB, OPERATORS);

        client.modifyDN(OPERATORS, "cn=admins", true);
        assertMemberOf(BOB, "cn=admins,ou=groups," + BASE);

        client.delete("cn=admins,ou=groups," + BASE);
        assertMemberOf(BOB);
        assertMemberOf(ALICE, DEVELOPERS);
    }

    @Test
    void memberOfIsOperationalSearchableAndReadOnlyForClients() throws Exception {
        start("memberOf");
        client.add(group(DEVELOPERS, "member", ALICE));

        Entry withUserAttributes = client.getEntry(ALICE);
        assertNull(withUserAttributes.getAttribute("memberOf"), "memberOf is operational, not returned for *");
        assertEquals(List.of(ALICE), client.search(BASE, SearchScope.SUB, "(memberOf=" + DEVELOPERS + ")")
                .getSearchEntries().stream().map(Entry::getDN).collect(Collectors.toList()));

        LDAPException modify = assertThrows(LDAPException.class, () -> client.modify(ALICE,
                new Modification(ModificationType.ADD, "memberOf", OPERATORS)));
        assertEquals(ResultCode.CONSTRAINT_VIOLATION, modify.getResultCode());
        LDAPException add = assertThrows(LDAPException.class, () -> client.add(new AddRequest(
                "uid=carol,ou=people," + BASE, Arrays.asList(
                        new com.unboundid.ldap.sdk.Attribute("objectClass", "inetOrgPerson"),
                        new com.unboundid.ldap.sdk.Attribute("cn", "Carol"),
                        new com.unboundid.ldap.sdk.Attribute("sn", "Example"),
                        new com.unboundid.ldap.sdk.Attribute("memberOf", DEVELOPERS)))));
        assertEquals(ResultCode.CONSTRAINT_VIOLATION, add.getResultCode());
        assertMemberOf(ALICE, DEVELOPERS);
    }

    @Test
    void directWritesAreSynchronizedBeforeClientReadsAndOnRequest() throws Exception {
        start("memberOf");
        server.directory().add(group(DEVELOPERS, "member", ALICE));
        assertNull(server.directory().getEntry(ALICE, "memberOf").getAttribute("memberOf"),
                "direct writes bypass the interceptor");

        assertEquals(1, client.search(BASE, SearchScope.SUB, "(memberOf=" + DEVELOPERS + ")").getEntryCount());
        assertMemberOf(ALICE, DEVELOPERS);

        server.directory().modify(DEVELOPERS, new Modification(ModificationType.ADD, "member", BOB));
        server.synchronizeMemberOf();
        assertMemberOf(BOB, DEVELOPERS);
    }

    @Test
    void snapshotCaptureSynchronizesAndRestoreRollsBack() throws Exception {
        start("memberOf");
        server.directory().add(group(DEVELOPERS, "member", ALICE));
        server.capture();
        assertMemberOf(ALICE, DEVELOPERS);

        client.modify(DEVELOPERS, new Modification(ModificationType.REPLACE, "member", BOB));
        assertMemberOf(ALICE);
        assertMemberOf(BOB, DEVELOPERS);

        server.restore();
        assertMemberOf(ALICE, DEVELOPERS);
        assertMemberOf(BOB);
    }

    @Test
    void followsConfiguredMembershipAttributesOnly() throws Exception {
        start("uniqueMember");
        client.add(group(DEVELOPERS, "uniqueMember", ALICE + "#'0101'B"));
        client.add(group(OPERATORS, "member", BOB));
        assertMemberOf(ALICE, DEVELOPERS);
        assertMemberOf(BOB);
    }

    @Test
    void danglingAndInvalidMembersAreIgnored() throws Exception {
        start("memberOf");
        client.add(group(DEVELOPERS, "member", "uid=nobody,ou=people," + BASE, ALICE));
        assertMemberOf(ALICE, DEVELOPERS);
        assertEquals(1, client.search(BASE, SearchScope.SUB, "(memberOf=*)").getEntryCount());
    }

    @Test
    void synchronizeRequiresTheOption() throws Exception {
        start("defaults");
        assertThrows(IllegalStateException.class, server::synchronizeMemberOf);
        client.add(group(DEVELOPERS, "member", ALICE));
        assertNull(server.directory().getSchema().getAttributeType("memberOf"));
    }

    private void start(String configuration) throws Exception {
        server = new LdapTestServer(Config.class.getDeclaredField(configuration).getAnnotation(InjectLdapTestServer.class));
        server.directory().add("dn: " + BASE, "objectClass: top", "objectClass: domain", "dc: example");
        server.directory().add("dn: ou=people," + BASE, "objectClass: organizationalUnit", "ou: people");
        server.directory().add("dn: ou=groups," + BASE, "objectClass: organizationalUnit", "ou: groups");
        for (String uid : List.of("alice", "bob")) {
            server.directory().add("dn: uid=" + uid + ",ou=people," + BASE, "objectClass: inetOrgPerson",
                    "cn: " + uid, "sn: Example", "uid: " + uid);
        }
        client = server.directory().getConnection();
        client.bind(server.getBindDn(), server.getBindPassword());
    }

    private static Entry group(String dn, String attribute, String... members) {
        String cn = dn.substring(3, dn.indexOf(','));
        Entry entry = new Entry(dn);
        entry.addAttribute("objectClass", attribute.equals("uniqueMember") ? "groupOfUniqueNames" : "groupOfNames");
        entry.addAttribute("cn", cn);
        entry.addAttribute(attribute, members);
        return entry;
    }

    private void assertMemberOf(String dn, String... groups) throws LDAPException {
        String[] values = server.directory().getEntry(dn, "memberOf").getAttributeValues("memberOf");
        List<String> actual = values == null ? List.of() : Arrays.asList(values);
        assertEquals(List.of(groups), actual, "memberOf of " + dn);
        assertTrue(client.getEntry(dn, "memberOf").hasAttribute("memberOf") == (groups.length > 0));
    }

    static final class Config {
        @InjectLdapTestServer
        LdapTestServer defaults;

        @InjectLdapTestServer(memberOf = true)
        LdapTestServer memberOf;

        @InjectLdapTestServer(memberOf = true, memberOfMembershipAttributes = "uniqueMember")
        LdapTestServer uniqueMember;
    }
}
