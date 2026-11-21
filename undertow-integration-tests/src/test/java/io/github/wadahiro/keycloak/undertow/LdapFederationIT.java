package io.github.wadahiro.keycloak.undertow;

import java.util.List;
import java.util.Map;

import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.annotations.TestSetup;
import org.keycloak.testframework.oauth.OAuthClient;
import org.keycloak.testframework.oauth.annotations.InjectOAuthClient;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.ui.annotations.InjectPage;
import org.keycloak.testframework.ui.page.LoginPage;

import io.github.wadahiro.keycloak.ldap.InjectLdapTestServer;
import io.github.wadahiro.keycloak.ldap.LdapTestServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KeycloakIntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LdapFederationIT {
    private static final String STORAGE_NAME = "in-memory-ldap";
    private static final String USER_DN = "uid=alice,ou=people,dc=example,dc=test";
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "alice-password";

    @InjectRealm
    ManagedRealm realm;

    @InjectH2Snapshot
    H2Snapshot snapshot;

    @InjectLdapTestServer
    LdapTestServer ldap;

    @InjectOAuthClient
    OAuthClient app;

    @InjectPage
    LoginPage loginPage;

    @TestSetup
    public void configureLdapFederation() throws Exception {
        if (!ldap.directory().entryExists("dc=example,dc=test")) {
            ldap.directory().add("dn: dc=example,dc=test", "objectClass: top", "objectClass: domain", "dc: example");
        }
        if (!ldap.directory().entryExists("ou=people,dc=example,dc=test")) {
            ldap.directory().add("dn: ou=people,dc=example,dc=test", "objectClass: organizationalUnit", "ou: people");
        }
        if (!ldap.directory().entryExists(USER_DN)) {
            ldap.directory().add("dn: " + USER_DN,
                    "objectClass: inetOrgPerson", "cn: Alice Example", "sn: Example", "uid: " + USERNAME,
                    "mail: alice@example.test", "departmentNumber: engineering", "userPassword: " + PASSWORD);
        }

        ComponentRepresentation storage = findStorage();
        if (storage == null) {
            storage = new ComponentRepresentation();
            storage.setName(STORAGE_NAME);
            storage.setProviderId("ldap");
            storage.setProviderType("org.keycloak.storage.UserStorageProvider");
            storage.setParentId(realm.admin().toRepresentation().getId());
            MultivaluedHashMap<String, String> config = new MultivaluedHashMap<>();
            config.putSingle("vendor", "other");
            config.putSingle("connectionUrl", ldap.getConnectionUrl());
            config.putSingle("usersDn", "ou=people,dc=example,dc=test");
            config.putSingle("bindDn", ldap.getBindDn());
            config.putSingle("bindCredential", ldap.getBindPassword());
            config.putSingle("authType", "simple");
            config.putSingle("usernameLDAPAttribute", "uid");
            config.putSingle("rdnLDAPAttribute", "uid");
            config.putSingle("uuidLDAPAttribute", "entryUUID");
            config.putSingle("userObjectClasses", "inetOrgPerson");
            config.putSingle("editMode", "READ_ONLY");
            config.putSingle("importEnabled", "true");
            config.putSingle("syncRegistrations", "false");
            config.putSingle("searchScope", "1");
            config.putSingle("pagination", "false");
            storage.setConfig(config);
            try (var response = realm.admin().components().add(storage)) {
                assertEquals(201, response.getStatus(), "Failed to configure LDAP federation: "
                        + response.readEntity(String.class));
                storage.setId(org.keycloak.testframework.util.ApiUtil.getCreatedId(response));
            }
        }

        if (realm.admin().components().query(storage.getId(), "org.keycloak.storage.ldap.mappers.LDAPStorageMapper",
                "department-attribute").isEmpty()) {
            ComponentRepresentation mapper = new ComponentRepresentation();
            mapper.setName("department-attribute");
            mapper.setProviderId("user-attribute-ldap-mapper");
            mapper.setProviderType("org.keycloak.storage.ldap.mappers.LDAPStorageMapper");
            mapper.setParentId(storage.getId());
            MultivaluedHashMap<String, String> config = new MultivaluedHashMap<>();
            config.putSingle("ldap.attribute", "departmentNumber");
            config.putSingle("user.model.attribute", "department");
            config.putSingle("read.only", "true");
            config.putSingle("always.read.value.from.ldap", "true");
            mapper.setConfig(config);
            try (var response = realm.admin().components().add(mapper)) {
                assertEquals(201, response.getStatus(), "Failed to configure LDAP user attribute mapper: "
                        + response.readEntity(String.class));
            }
        }

        snapshot.capture();
    }

    @Test
    @Order(1)
    void ldapUserCanAuthenticateAndMapperReadsDirectoryAttribute() throws Exception {
        authenticateAndAssertDepartment("engineering");

        ldap.directory().modify(USER_DN,
                new Modification(ModificationType.REPLACE, "departmentNumber", "changed-by-test"));
        assertEquals("changed-by-test", ldap.directory().getEntry(USER_DN).getAttributeValue("departmentNumber"));
    }

    @Test
    @Order(2)
    void h2RestoreAlsoRestoresLdapSnapshot() throws Exception {
        assertEquals("engineering", ldap.directory().getEntry(USER_DN).getAttributeValue("departmentNumber"));
        authenticateAndAssertDepartment("engineering");
    }

    private void authenticateAndAssertDepartment(String expectedDepartment) {
        app.openLoginForm();
        loginPage.assertCurrent();
        loginPage.fillLogin(USERNAME, PASSWORD);
        loginPage.submit();
        var authorization = app.parseLoginResponse();
        assertTrue(authorization.isRedirected(), "LDAP login did not return to the OIDC client");
        var tokens = app.doAccessTokenRequest(authorization.getCode());
        assertTrue(tokens.isSuccess(), tokens.getErrorDescription());

        var users = realm.admin().users().search(USERNAME);
        assertEquals(1, users.size());
        Map<String, List<String>> attributes = users.get(0).getAttributes();
        assertEquals(List.of(expectedDepartment), attributes.get("department"));
    }

    private ComponentRepresentation findStorage() {
        return realm.admin().components().query(realm.admin().toRepresentation().getId(),
                "org.keycloak.storage.UserStorageProvider", STORAGE_NAME).stream().findFirst().orElse(null);
    }
}
