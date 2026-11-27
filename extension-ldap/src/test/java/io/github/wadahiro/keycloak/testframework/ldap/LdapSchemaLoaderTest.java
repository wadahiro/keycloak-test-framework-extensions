package io.github.wadahiro.keycloak.testframework.ldap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassType;
import com.unboundid.ldap.sdk.schema.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LdapSchemaLoaderTest {
    private static final ClassLoader LOADER = LdapSchemaLoaderTest.class.getClassLoader();

    @Test
    void convertsOpenLdapSchemaWithMacrosCommentsAndContinuations() {
        Schema schema = LdapSchemaLoader.load(LOADER, "ldap/x-example.schema");

        AttributeTypeDefinition badge = schema.getAttributeType("x-example-badgeNumber");
        assertNotNull(badge);
        assertEquals("1.3.6.1.4.1.32473.1.1.1", badge.getOID());
        assertEquals("Badge number of a person", badge.getDescription());
        assertEquals("1.3.6.1.4.1.1466.115.121.1.15{64}", badge.getSyntaxOID());
        assertTrue(badge.isSingleValued());
        assertEquals("1.3.6.1.4.1.32473.1.1.2", schema.getAttributeType("x-example-project").getOID());

        ObjectClassDefinition person = schema.getObjectClass("x-example-person");
        assertEquals("1.3.6.1.4.1.32473.1.2.1", person.getOID());
        assertEquals(ObjectClassType.AUXILIARY, person.getObjectClassType());
        assertEquals(2, person.getOptionalAttributes().length);
    }

    @Test
    void readsOpenLdapConfigLdifAndSubschemaLdif() {
        Schema config = LdapSchemaLoader.load(LOADER, "/ldap/x-example-config.ldif");
        assertEquals("1.3.6.1.4.1.32473.1.1.1", config.getAttributeType("x-example-badgeNumber").getOID());
        assertEquals("Badge number of a person", config.getAttributeType("x-example-badgeNumber").getDescription());
        assertEquals("1.3.6.1.4.1.32473.1.2.1", config.getObjectClass("x-example-person").getOID());
        assertEquals("Example auxiliary class for people", config.getObjectClass("x-example-person").getDescription());
        assertNull(config.getObjectClass("olcSchemaConfig"));

        Schema merged = LdapSchemaLoader.load(LOADER, "ldap/x-example.schema", "ldap/x-example-subschema.ldif");
        assertNotNull(merged.getAttributeType("x-example-badgeNumber"));
        assertNotNull(merged.getAttributeType("x-example-costCenter"));
        assertNotNull(merged.getObjectClass("x-example-account"));
    }

    @Test
    void readsFileSystemPathsAndModifyRecords(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("modify.ldif");
        Files.writeString(file, String.join("\n",
                "dn: cn=x-example,cn=schema,cn=config",
                "changetype: modify",
                "add: olcAttributeTypes",
                "olcAttributeTypes: ( 1.3.6.1.4.1.32473.1.1.9 NAME 'x-example-locker'"
                        + " SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )",
                ""));
        Schema schema = LdapSchemaLoader.load(LOADER, "file:" + file);
        assertEquals("1.3.6.1.4.1.32473.1.1.9", schema.getAttributeType("x-example-locker").getOID());
    }

    @Test
    void acceptsQuotedSyntaxAndKeepsDescriptionsVerbatim() {
        Schema schema = LdapSchemaLoader.parseOpenLdapSchema(
                "attributetype ( 1.3.6.1.4.1.32473.1.1.8 NAME 'x-example-note' DESC 'a ( note ) #1'"
                        + " SYNTAX '1.3.6.1.4.1.1466.115.121.1.15' )", "inline");
        AttributeTypeDefinition note = schema.getAttributeType("x-example-note");
        assertEquals("a ( note ) #1", note.getDescription());
        assertEquals("1.3.6.1.4.1.1466.115.121.1.15", note.getSyntaxOID());
    }

    @Test
    void reportsInvalidInputWithItsSource() {
        IllegalArgumentException directive = assertThrows(IllegalArgumentException.class,
                () -> LdapSchemaLoader.parseOpenLdapSchema("include other.schema", "inline.schema"));
        assertTrue(directive.getMessage().contains("inline.schema"), directive.getMessage());
        assertTrue(directive.getMessage().contains("include"), directive.getMessage());

        IllegalArgumentException macro = assertThrows(IllegalArgumentException.class,
                () -> LdapSchemaLoader.parseOpenLdapSchema(
                        "attributetype ( unknownMacro:1 NAME 'x-example-a' )", "inline.schema"));
        assertTrue(macro.getMessage().contains("unknownMacro:1"), macro.getMessage());

        IllegalArgumentException definition = assertThrows(IllegalArgumentException.class,
                () -> LdapSchemaLoader.parseOpenLdapSchema(
                        "objectclass ( 1.3.6.1.4.1.32473.1.2.9 NAME 'x-example-broken' MAY ( a $ ) ", "inline.schema"));
        assertTrue(definition.getMessage().contains("inline.schema"), definition.getMessage());

        assertThrows(IllegalArgumentException.class, () -> LdapSchemaLoader.load(LOADER, "ldap/missing.schema"));
        assertThrows(IllegalArgumentException.class, () -> LdapSchemaLoader.load(LOADER, "ldap/x-example.txt"));
    }

    @Test
    void serverEnforcesMergedSchema() throws Exception {
        try (LdapTestServer server = new LdapTestServer(Config.annotation("withSchema"))) {
            server.directory().add("dn: dc=example,dc=test", "objectClass: top", "objectClass: domain", "dc: example");
            server.directory().add("dn: uid=alice,dc=example,dc=test", "objectClass: inetOrgPerson",
                    "objectClass: x-example-person", "cn: Alice", "sn: Example", "uid: alice",
                    "x-example-badgeNumber: B-1001", "x-example-project: alpha", "x-example-project: beta");
            assertEquals("B-1001", server.directory().getEntry("uid=alice,dc=example,dc=test")
                    .getAttributeValue("x-example-badgeNumber"));

            LDAPException withoutClass = assertThrows(LDAPException.class, () -> server.directory().add(
                    "dn: uid=bob,dc=example,dc=test", "objectClass: inetOrgPerson", "cn: Bob", "sn: Example",
                    "uid: bob", "x-example-badgeNumber: B-1002"));
            assertEquals(ResultCode.OBJECT_CLASS_VIOLATION, withoutClass.getResultCode());

            LDAPException singleValued = assertThrows(LDAPException.class, () -> server.directory().add(
                    "dn: uid=carol,dc=example,dc=test", "objectClass: inetOrgPerson", "objectClass: x-example-person",
                    "cn: Carol", "sn: Example", "uid: carol",
                    "x-example-badgeNumber: B-1", "x-example-badgeNumber: B-2"));
            assertEquals(ResultCode.OBJECT_CLASS_VIOLATION, singleValued.getResultCode());

            assertNotNull(server.directory().getSchema().getAttributeType("cn"), "standard schema is kept");
            assertNull(server.directory().getSchema().getAttributeType("memberOf"));
        }
    }

    @Test
    void defaultServerKeepsStandardSchemaOnly() throws Exception {
        try (LdapTestServer server = new LdapTestServer(Config.annotation("defaults"))) {
            Schema schema = server.directory().getSchema();
            assertNull(schema.getAttributeType("x-example-badgeNumber"));
            assertNull(schema.getAttributeType("memberOf"));
            assertEquals(Schema.getDefaultStandardSchema().getAttributeTypes().size(), schema.getAttributeTypes().size());
        }
    }

    static final class Config {
        @InjectLdapTestServer
        LdapTestServer defaults;

        @InjectLdapTestServer(schema = {"ldap/x-example.schema", "ldap/x-example-subschema.ldif"})
        LdapTestServer withSchema;

        static InjectLdapTestServer annotation(String field) throws NoSuchFieldException {
            return Config.class.getDeclaredField(field).getAnnotation(InjectLdapTestServer.class);
        }
    }
}
