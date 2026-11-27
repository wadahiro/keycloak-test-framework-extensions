package io.github.wadahiro.keycloak.testframework.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import io.github.wadahiro.keycloak.testframework.ldap.InjectLdapTestServer;
import io.github.wadahiro.keycloak.testframework.ldap.LdapTestServer;
import io.github.wadahiro.keycloak.testframework.snapshot.InjectSnapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.Snapshot;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.annotations.TestSetup;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.util.ApiUtil;

/**
 * LDAP federation against a directory with a custom schema (OpenLDAP {@code cn=config} LDIF) and emulated
 * {@code memberOf}: a user-attribute mapper reads and writes a custom attribute, a group mapper reads
 * memberships from {@code memberOf}, and the user search filter selects users by {@code memberOf}.
 */
@KeycloakIntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LdapSchemaMemberOfIT {
    private static final String BASE_DN = "dc=example,dc=test";
    private static final String PEOPLE_DN = "ou=people," + BASE_DN;
    private static final String GROUPS_DN = "ou=groups," + BASE_DN;
    private static final String ALICE_DN = "uid=alice," + PEOPLE_DN;
    private static final String BOB_DN = "uid=bob," + PEOPLE_DN;
    private static final String DEVELOPERS_DN = "cn=developers," + GROUPS_DN;
    private static final String OPERATORS_DN = "cn=operators," + GROUPS_DN;
    private static final String STORAGE_NAME = "schema-memberof-ldap";
    private static final String GROUP_MAPPER_NAME = "groups-from-memberof";
    private static final String MAPPER_TYPE = "org.keycloak.storage.ldap.mappers.LDAPStorageMapper";

    @InjectRealm
    ManagedRealm realm;

    @InjectSnapshot
    Snapshot snapshot;

    @InjectLdapTestServer(schema = "ldap/x-example.ldif", memberOf = true)
    LdapTestServer ldap;

    @TestSetup
    public void configureLdapFederation() throws Exception {
        if (!ldap.directory().entryExists(BASE_DN)) {
            ldap.directory().add("dn: " + BASE_DN, "objectClass: top", "objectClass: domain", "dc: example");
            ldap.directory().add("dn: " + PEOPLE_DN, "objectClass: organizationalUnit", "ou: people");
            ldap.directory().add("dn: " + GROUPS_DN, "objectClass: organizationalUnit", "ou: groups");
            ldap.directory().add("dn: " + ALICE_DN, "objectClass: inetOrgPerson", "objectClass: x-example-person",
                    "cn: Alice Example", "sn: Example", "uid: alice", "x-example-badgeNumber: B-1001");
            ldap.directory().add("dn: " + BOB_DN, "objectClass: inetOrgPerson", "objectClass: x-example-person",
                    "cn: Bob Example", "sn: Example", "uid: bob", "x-example-badgeNumber: B-1002");
            ldap.directory().add("dn: " + DEVELOPERS_DN, "objectClass: groupOfNames", "cn: developers",
                    "member: " + ALICE_DN);
            ldap.directory().add("dn: " + OPERATORS_DN, "objectClass: groupOfNames", "cn: operators",
                    "member: " + BOB_DN);
        }

        String realmId = realm.admin().toRepresentation().getId();
        ComponentRepresentation storage = realm.admin().components()
                .query(realmId, "org.keycloak.storage.UserStorageProvider", STORAGE_NAME).stream().findFirst().orElse(null);
        if (storage != null) return;

        storage = new ComponentRepresentation();
        storage.setName(STORAGE_NAME);
        storage.setProviderId("ldap");
        storage.setProviderType("org.keycloak.storage.UserStorageProvider");
        storage.setParentId(realmId);
        MultivaluedHashMap<String, String> config = new MultivaluedHashMap<>();
        config.putSingle("vendor", "other");
        config.putSingle("connectionUrl", ldap.getConnectionUrl());
        config.putSingle("usersDn", PEOPLE_DN);
        config.putSingle("bindDn", ldap.getBindDn());
        config.putSingle("bindCredential", ldap.getBindPassword());
        config.putSingle("authType", "simple");
        config.putSingle("usernameLDAPAttribute", "uid");
        config.putSingle("rdnLDAPAttribute", "uid");
        config.putSingle("uuidLDAPAttribute", "entryUUID");
        config.putSingle("userObjectClasses", "inetOrgPerson, x-example-person");
        config.putSingle("customUserSearchFilter", "(memberOf=" + DEVELOPERS_DN + ")");
        config.putSingle("editMode", "WRITABLE");
        config.putSingle("importEnabled", "true");
        config.putSingle("syncRegistrations", "false");
        config.putSingle("searchScope", "1");
        config.putSingle("pagination", "false");
        storage.setConfig(config);
        try (var response = realm.admin().components().add(storage)) {
            assertEquals(201, response.getStatus(), response.readEntity(String.class));
            storage.setId(ApiUtil.getCreatedId(response));
        }

        MultivaluedHashMap<String, String> badge = new MultivaluedHashMap<>();
        badge.putSingle("ldap.attribute", "x-example-badgeNumber");
        badge.putSingle("user.model.attribute", "badgeNumber");
        badge.putSingle("read.only", "false");
        badge.putSingle("always.read.value.from.ldap", "true");
        badge.putSingle("is.mandatory.in.ldap", "false");
        addMapper(storage.getId(), "badge-number", "user-attribute-ldap-mapper", badge);

        MultivaluedHashMap<String, String> groups = new MultivaluedHashMap<>();
        groups.putSingle("groups.dn", GROUPS_DN);
        groups.putSingle("group.name.ldap.attribute", "cn");
        groups.putSingle("group.object.classes", "groupOfNames");
        groups.putSingle("preserve.group.inheritance", "false");
        groups.putSingle("ignore.missing.groups", "false");
        groups.putSingle("membership.ldap.attribute", "member");
        groups.putSingle("membership.attribute.type", "DN");
        groups.putSingle("membership.user.ldap.attribute", "uid");
        groups.putSingle("mode", "LDAP_ONLY");
        groups.putSingle("user.roles.retrieve.strategy", "GET_GROUPS_FROM_USER_MEMBEROF_ATTRIBUTE");
        groups.putSingle("memberof.ldap.attribute", "memberOf");
        groups.putSingle("drop.non.existing.groups.during.sync", "false");
        groups.putSingle("groups.path", "/");
        String groupMapperId = addMapper(storage.getId(), GROUP_MAPPER_NAME, "group-ldap-mapper", groups);
        realm.admin().userStorage().syncMapperData(storage.getId(), groupMapperId, "fedToKeycloak");
    }

    @Test
    @Order(1)
    void mapsCustomAttributeAndGroupsFromMemberOf() throws Exception {
        assertEquals(List.of(DEVELOPERS_DN), memberOf(ALICE_DN));
        assertEquals(List.of(OPERATORS_DN), memberOf(BOB_DN));

        UserResource alice = user("alice");
        assertEquals(List.of("B-1001"), alice.toRepresentation().getAttributes().get("badgeNumber"));
        assertEquals(List.of("developers"), groupNames(alice));
        assertEquals(List.of(), realm.admin().users().search("bob"), "the memberOf search filter excludes bob");
    }

    @Test
    @Order(2)
    void writesCustomAttributeAndGroupMembershipToLdap() throws Exception {
        UserResource alice = user("alice");
        UserRepresentation representation = alice.toRepresentation();
        representation.singleAttribute("badgeNumber", "B-2001");
        alice.update(representation);
        assertEquals("B-2001", ldap.directory().getEntry(ALICE_DN).getAttributeValue("x-example-badgeNumber"));

        GroupRepresentation operators = realm.admin().groups().groups().stream()
                .filter(group -> group.getName().equals("operators")).findFirst().orElseThrow();
        alice.joinGroup(operators.getId());
        assertEquals(List.of(ALICE_DN, BOB_DN), Arrays.stream(ldap.directory().getEntry(OPERATORS_DN)
                .getAttributeValues("member")).sorted().collect(Collectors.toList()));
        assertEquals(List.of(DEVELOPERS_DN, OPERATORS_DN), memberOf(ALICE_DN));
        assertEquals(List.of("developers", "operators"), groupNames(user("alice")));
        assertEquals(List.of("B-2001"), user("alice").toRepresentation().getAttributes().get("badgeNumber"));
    }

    @Test
    @Order(3)
    void snapshotRestoresDirectoryAndMemberOf() throws Exception {
        assertEquals("B-1001", ldap.directory().getEntry(ALICE_DN).getAttributeValue("x-example-badgeNumber"));
        assertEquals(List.of(DEVELOPERS_DN), memberOf(ALICE_DN));
        assertEquals(List.of("developers"), groupNames(user("alice")));
    }

    private String addMapper(String storageId, String name, String providerId, MultivaluedHashMap<String, String> config) {
        ComponentRepresentation mapper = new ComponentRepresentation();
        mapper.setName(name);
        mapper.setProviderId(providerId);
        mapper.setProviderType(MAPPER_TYPE);
        mapper.setParentId(storageId);
        mapper.setConfig(config);
        try (var response = realm.admin().components().add(mapper)) {
            assertEquals(201, response.getStatus(), response.readEntity(String.class));
            return ApiUtil.getCreatedId(response);
        }
    }

    private UserResource user(String username) {
        List<UserRepresentation> users = realm.admin().users().search(username, true);
        assertEquals(1, users.size(), "LDAP user " + username);
        return realm.admin().users().get(users.get(0).getId());
    }

    private static List<String> groupNames(UserResource user) {
        return user.groups().stream().map(GroupRepresentation::getName).sorted().collect(Collectors.toList());
    }

    private List<String> memberOf(String dn) throws Exception {
        String[] values = ldap.directory().getEntry(dn, "memberOf").getAttributeValues("memberOf");
        return values == null ? List.of() : Arrays.asList(values);
    }
}
