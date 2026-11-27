package io.github.wadahiro.keycloak.testframework.ldap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.schema.AttributeSyntaxDefinition;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.DITContentRuleDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFAddChangeRecord;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFModifyChangeRecord;
import com.unboundid.ldif.LDIFReader;
import com.unboundid.ldif.LDIFRecord;

/**
 * Reads custom LDAP schema definitions for the in-memory test directory.
 *
 * <p>Two input formats are supported:
 * <ul>
 * <li>LDIF ({@code .ldif}): attribute values named {@code attributeTypes}, {@code objectClasses},
 * {@code ldapSyntaxes} and {@code dITContentRules} (RFC 4512 subschema form), or their OpenLDAP
 * {@code cn=config} counterparts {@code olcAttributeTypes}, {@code olcObjectClasses},
 * {@code olcLdapSyntaxes}, {@code olcDitContentRules} and {@code olcObjectIdentifier}. Entry records and
 * {@code changetype: modify} records are accepted; {@code {N}} ordering prefixes are removed.</li>
 * <li>OpenLDAP {@code slapd.conf} schema ({@code .schema}): {@code attributetype}, {@code objectclass},
 * {@code objectidentifier}, {@code ldapsyntax} and {@code ditcontentrule} directives, with {@code #}
 * comment lines and whitespace continuation lines.</li>
 * </ul>
 * OID macros declared with {@code objectidentifier}/{@code olcObjectIdentifier} are expanded in the OID
 * position and in {@code SYNTAX}. Every definition is parsed strictly: an invalid definition fails with the
 * source name instead of being ignored.
 */
public final class LdapSchemaLoader {
    private static final String FILE_PREFIX = "file:";
    private static final Pattern NUMERIC_OID = Pattern.compile("[0-9]+(\\.[0-9]+)*");
    private static final Pattern ORDER_PREFIX = Pattern.compile("^\\{\\d+}\\s*");
    private static final Map<String, String> DEFINITION_ATTRIBUTES = Map.ofEntries(
            Map.entry("attributetypes", Schema.ATTR_ATTRIBUTE_TYPE),
            Map.entry("olcattributetypes", Schema.ATTR_ATTRIBUTE_TYPE),
            Map.entry("objectclasses", Schema.ATTR_OBJECT_CLASS),
            Map.entry("olcobjectclasses", Schema.ATTR_OBJECT_CLASS),
            Map.entry("ldapsyntaxes", Schema.ATTR_ATTRIBUTE_SYNTAX),
            Map.entry("olcldapsyntaxes", Schema.ATTR_ATTRIBUTE_SYNTAX),
            Map.entry("ditcontentrules", Schema.ATTR_DIT_CONTENT_RULE),
            Map.entry("olcditcontentrules", Schema.ATTR_DIT_CONTENT_RULE));
    private static final Map<String, String> SCHEMA_DIRECTIVES = Map.ofEntries(
            Map.entry("attributetype", Schema.ATTR_ATTRIBUTE_TYPE),
            Map.entry("attributetypes", Schema.ATTR_ATTRIBUTE_TYPE),
            Map.entry("objectclass", Schema.ATTR_OBJECT_CLASS),
            Map.entry("objectclasses", Schema.ATTR_OBJECT_CLASS),
            Map.entry("ldapsyntax", Schema.ATTR_ATTRIBUTE_SYNTAX),
            Map.entry("ldapsyntaxes", Schema.ATTR_ATTRIBUTE_SYNTAX),
            Map.entry("ditcontentrule", Schema.ATTR_DIT_CONTENT_RULE),
            Map.entry("ditcontentrules", Schema.ATTR_DIT_CONTENT_RULE));

    private LdapSchemaLoader() {}

    /**
     * Loads and merges schema files. Plain values are classpath resources of {@code classLoader};
     * values prefixed with {@code file:} are file system paths. The format follows the file extension.
     *
     * @return the merged custom definitions, without the standard schema
     */
    public static Schema load(ClassLoader classLoader, String... locations) {
        if (locations.length == 0) throw new IllegalArgumentException("No LDAP schema location was given");
        List<Schema> schemas = new ArrayList<>();
        for (String location : locations) {
            String text = read(classLoader, location);
            String lower = location.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".ldif")) {
                schemas.add(parseLdifSchema(text, location));
            } else if (lower.endsWith(".schema")) {
                schemas.add(parseOpenLdapSchema(text, location));
            } else {
                throw new IllegalArgumentException("Unsupported LDAP schema file " + location
                        + "; use a .ldif or an OpenLDAP .schema file");
            }
        }
        return Schema.mergeSchemas(schemas.toArray(Schema[]::new));
    }

    /** Parses OpenLDAP {@code slapd.conf} schema text, such as the content of a {@code .schema} file. */
    public static Schema parseOpenLdapSchema(String text, String sourceName) {
        Definitions definitions = new Definitions(sourceName);
        for (String line : logicalLines(text)) {
            String[] parts = line.split("\\s+", 2);
            String directive = parts[0].toLowerCase(Locale.ROOT);
            String value = parts.length > 1 ? parts[1].trim() : "";
            if (directive.equals("objectidentifier")) {
                definitions.addMacro(value);
            } else if (SCHEMA_DIRECTIVES.containsKey(directive)) {
                definitions.add(SCHEMA_DIRECTIVES.get(directive), value);
            } else {
                throw new IllegalArgumentException(sourceName + ": unsupported schema directive '" + parts[0]
                        + "'; supported are attributetype, objectclass, objectidentifier, ldapsyntax and ditcontentrule");
            }
        }
        return definitions.toSchema();
    }

    /** Parses LDIF schema records, either RFC 4512 subschema attributes or OpenLDAP {@code cn=config} entries. */
    public static Schema parseLdifSchema(String text, String sourceName) {
        Definitions definitions = new Definitions(sourceName);
        List<Attribute> attributes = new ArrayList<>();
        try (LDIFReader reader = new LDIFReader(new BufferedReader(new StringReader(text)))) {
            LDIFRecord record;
            while ((record = reader.readLDIFRecord()) != null) {
                if (record instanceof Entry) {
                    attributes.addAll(((Entry) record).getAttributes());
                } else if (record instanceof LDIFAddChangeRecord) {
                    attributes.addAll(((LDIFAddChangeRecord) record).getEntryToAdd().getAttributes());
                } else if (record instanceof LDIFModifyChangeRecord) {
                    for (Modification modification : ((LDIFModifyChangeRecord) record).getModifications()) {
                        if (modification.getModificationType() == ModificationType.ADD
                                || modification.getModificationType() == ModificationType.REPLACE) {
                            attributes.add(modification.getAttribute());
                        }
                    }
                } else {
                    throw new IllegalArgumentException(sourceName + ": unsupported LDIF change record for "
                            + record.getDN());
                }
            }
        } catch (LDIFException e) {
            throw new IllegalArgumentException(sourceName + ": invalid LDIF: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Attribute attribute : attributes) {
            if (attribute.getBaseName().equalsIgnoreCase("olcObjectIdentifier")) {
                for (String value : attribute.getValues()) definitions.addMacro(stripOrder(value));
            }
        }
        for (Attribute attribute : attributes) {
            String target = DEFINITION_ATTRIBUTES.get(attribute.getBaseName().toLowerCase(Locale.ROOT));
            if (target == null) continue;
            for (String value : attribute.getValues()) definitions.add(target, stripOrder(value));
        }
        return definitions.toSchema();
    }

    private static String read(ClassLoader classLoader, String location) {
        try {
            if (location.startsWith(FILE_PREFIX)) {
                return Files.readString(Path.of(location.substring(FILE_PREFIX.length())), StandardCharsets.UTF_8);
            }
            String resource = location.startsWith("/") ? location.substring(1) : location;
            try (InputStream in = classLoader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalArgumentException("LDAP schema resource not found on the classpath: " + location
                            + " (prefix file system paths with " + FILE_PREFIX + ")");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("LDAP schema file not found: " + location, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read LDAP schema " + location, e);
        }
    }

    private static List<String> logicalLines(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = null;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (Character.isWhitespace(line.charAt(0)) && current != null) {
                current.append(' ').append(trimmed);
            } else {
                if (current != null) lines.add(current.toString());
                current = new StringBuilder(trimmed);
            }
        }
        if (current != null) lines.add(current.toString());
        return lines;
    }

    private static String stripOrder(String value) {
        return ORDER_PREFIX.matcher(value.trim()).replaceFirst("");
    }

    /** Collects definitions, expands OID macros and validates each definition strictly. */
    private static final class Definitions {
        private final String sourceName;
        private final Map<String, String> macros = new LinkedHashMap<>();
        private final Map<String, List<String>> values = new LinkedHashMap<>();

        Definitions(String sourceName) {
            this.sourceName = sourceName;
        }

        void addMacro(String declaration) {
            String[] parts = declaration.trim().split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException(sourceName + ": invalid objectidentifier '" + declaration
                        + "'; expected '<name> <oid or name:suffix>'");
            }
            macros.put(parts[0].toLowerCase(Locale.ROOT), resolveOid(parts[1]));
        }

        void add(String attributeName, String definition) {
            values.computeIfAbsent(attributeName, k -> new ArrayList<>()).add(expand(definition));
        }

        Schema toSchema() {
            Entry entry = new Entry("cn=schema");
            entry.addAttribute("objectClass", "top", "ldapSubentry", "subschema");
            values.forEach((name, definitions) -> {
                for (String definition : definitions) validate(name, definition);
                entry.addAttribute(name, definitions);
            });
            return new Schema(entry);
        }

        private void validate(String attributeName, String definition) {
            try {
                switch (attributeName) {
                    case Schema.ATTR_ATTRIBUTE_TYPE: new AttributeTypeDefinition(definition); break;
                    case Schema.ATTR_OBJECT_CLASS: new ObjectClassDefinition(definition); break;
                    case Schema.ATTR_ATTRIBUTE_SYNTAX: new AttributeSyntaxDefinition(definition); break;
                    case Schema.ATTR_DIT_CONTENT_RULE: new DITContentRuleDefinition(definition); break;
                    default: throw new IllegalStateException(attributeName);
                }
            } catch (LDAPException e) {
                throw new IllegalArgumentException(sourceName + ": invalid " + attributeName + " definition "
                        + definition + ": " + e.getExceptionMessage(), e);
            }
        }

        private String expand(String definition) {
            List<String> tokens = tokenize(definition);
            if (tokens.isEmpty() || !tokens.get(0).equals("(") || !tokens.get(tokens.size() - 1).equals(")")) {
                throw new IllegalArgumentException(sourceName + ": schema definition must be enclosed in parentheses: "
                        + definition);
            }
            if (tokens.size() > 2) tokens.set(1, resolveOid(unquote(tokens.get(1))));
            for (int i = 2; i < tokens.size() - 1; i++) {
                if (tokens.get(i).equalsIgnoreCase("SYNTAX")) {
                    String syntax = unquote(tokens.get(i + 1));
                    int length = syntax.indexOf('{');
                    tokens.set(i + 1, length < 0 ? resolveOid(syntax)
                            : resolveOid(syntax.substring(0, length)) + syntax.substring(length));
                }
            }
            return String.join(" ", tokens);
        }

        private static String unquote(String token) {
            return token.length() > 1 && token.startsWith("'") && token.endsWith("'")
                    ? token.substring(1, token.length() - 1) : token;
        }

        private String resolveOid(String value) {
            if (NUMERIC_OID.matcher(value).matches()) return value;
            int colon = value.indexOf(':');
            if (colon > 0) {
                String base = macros.get(value.substring(0, colon).toLowerCase(Locale.ROOT));
                if (base == null) {
                    throw new IllegalArgumentException(sourceName + ": undefined objectidentifier in " + value);
                }
                return base + "." + value.substring(colon + 1);
            }
            return macros.getOrDefault(value.toLowerCase(Locale.ROOT), value);
        }

        private List<String> tokenize(String definition) {
            List<String> tokens = new ArrayList<>();
            int i = 0;
            int n = definition.length();
            while (i < n) {
                char c = definition.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '(' || c == ')') {
                    tokens.add(String.valueOf(c));
                    i++;
                } else if (c == '\'') {
                    int end = definition.indexOf('\'', i + 1);
                    if (end < 0) {
                        throw new IllegalArgumentException(sourceName + ": unterminated quoted string in " + definition);
                    }
                    tokens.add(definition.substring(i, end + 1));
                    i = end + 1;
                } else {
                    int start = i;
                    while (i < n && !Character.isWhitespace(definition.charAt(i))
                            && definition.charAt(i) != '(' && definition.charAt(i) != ')'
                            && definition.charAt(i) != '\'') {
                        i++;
                    }
                    tokens.add(definition.substring(start, i));
                }
            }
            return tokens;
        }
    }
}
