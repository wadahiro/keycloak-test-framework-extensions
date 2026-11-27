package io.github.wadahiro.keycloak.testframework.ldap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedAddRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedAddResult;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedCompareRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedDeleteResult;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedExtendedResult;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedModifyDNResult;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedModifyRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedModifyResult;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;

/**
 * Emulates a memberof overlay: keeps {@code memberOf} on member entries equal to the DNs of the groups that list
 * them in one of the configured membership attributes.
 *
 * <p>Client writes reach the server through the listener, so the interceptor recomputes after every successful
 * add, modify, delete, modify DN and extended (transaction) operation and before every search and compare.
 * Direct {@link InMemoryDirectoryServer} calls bypass interceptors; {@link #synchronize()} covers them.
 * Clients cannot write {@code memberOf}.
 */
final class MemberOfSynchronizer extends InMemoryOperationInterceptor {
    static final String MEMBER_OF = "memberOf";
    static final AttributeTypeDefinition MEMBER_OF_TYPE = memberOfType();
    private static final Pattern OPTIONAL_UID = Pattern.compile("#'[01]*'B$");

    private final List<String> membershipAttributes;
    private volatile InMemoryDirectoryServer directory;

    MemberOfSynchronizer(List<String> membershipAttributes) {
        if (membershipAttributes.isEmpty()) {
            throw new IllegalArgumentException("memberOf emulation needs at least one membership attribute");
        }
        this.membershipAttributes = List.copyOf(membershipAttributes);
    }

    void attach(InMemoryDirectoryServer directory) {
        this.directory = directory;
    }

    /** Recomputes {@code memberOf} for the whole directory; only entries whose value set changes are written. */
    synchronized void synchronize() throws LDAPException {
        InMemoryDirectoryServer server = directory;
        if (server == null) return;
        List<Filter> presence = new ArrayList<>();
        for (String attribute : membershipAttributes) presence.add(Filter.createPresenceFilter(attribute));
        presence.add(Filter.createPresenceFilter(MEMBER_OF));
        String[] returning = new String[membershipAttributes.size() + 1];
        membershipAttributes.toArray(returning);
        returning[returning.length - 1] = MEMBER_OF;

        Map<DN, Set<DN>> current = new LinkedHashMap<>();
        Map<DN, Set<DN>> desired = new LinkedHashMap<>();
        Map<DN, String> groupNames = new LinkedHashMap<>();
        for (DN base : server.getBaseDNs()) {
            if (!server.entryExists(base.toString())) continue;
            SearchRequest search = new SearchRequest(base.toString(), SearchScope.SUB,
                    Filter.createORFilter(presence), returning);
            for (SearchResultEntry entry : server.search(search).getSearchEntries()) {
                DN entryDn = entry.getParsedDN();
                Set<DN> memberOf = dnValues(entry.getAttribute(MEMBER_OF), false);
                if (!memberOf.isEmpty()) current.put(entryDn, memberOf);
                for (String attribute : membershipAttributes) {
                    for (DN member : dnValues(entry.getAttribute(attribute), true)) {
                        groupNames.putIfAbsent(entryDn, entry.getDN());
                        desired.computeIfAbsent(member, k -> new LinkedHashSet<>()).add(entryDn);
                    }
                }
            }
        }

        Set<DN> targets = new LinkedHashSet<>(current.keySet());
        targets.addAll(desired.keySet());
        for (DN target : targets) {
            Set<DN> wanted = desired.getOrDefault(target, Set.of());
            if (wanted.equals(current.getOrDefault(target, Set.of()))) continue;
            if (!server.entryExists(target.toString())) continue;
            List<String> values = new ArrayList<>();
            for (DN group : wanted) values.add(groupNames.get(group));
            server.modify(target.toString(), values.isEmpty()
                    ? new Modification(ModificationType.REPLACE, MEMBER_OF)
                    : new Modification(ModificationType.REPLACE, MEMBER_OF, values.toArray(String[]::new)));
        }
    }

    @Override
    public void processAddRequest(InMemoryInterceptedAddRequest request) throws LDAPException {
        if (request.getRequest().hasAttribute(MEMBER_OF)) throw readOnly();
    }

    @Override
    public void processModifyRequest(InMemoryInterceptedModifyRequest request) throws LDAPException {
        for (Modification modification : request.getRequest().getModifications()) {
            if (new Attribute(modification.getAttributeName()).getBaseName().equalsIgnoreCase(MEMBER_OF)) {
                throw readOnly();
            }
        }
    }

    @Override
    public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
        synchronize();
    }

    @Override
    public void processCompareRequest(InMemoryInterceptedCompareRequest request) throws LDAPException {
        synchronize();
    }

    @Override
    public void processAddResult(InMemoryInterceptedAddResult result) {
        afterWrite(result.getResult());
    }

    @Override
    public void processModifyResult(InMemoryInterceptedModifyResult result) {
        afterWrite(result.getResult());
    }

    @Override
    public void processDeleteResult(InMemoryInterceptedDeleteResult result) {
        afterWrite(result.getResult());
    }

    @Override
    public void processModifyDNResult(InMemoryInterceptedModifyDNResult result) {
        afterWrite(result.getResult());
    }

    @Override
    public void processExtendedResult(InMemoryInterceptedExtendedResult result) {
        afterWrite(result.getResult());
    }

    private void afterWrite(LDAPResult result) {
        if (result.getResultCode() != ResultCode.SUCCESS) return;
        try {
            synchronize();
        } catch (LDAPException e) {
            // The next search or compare retries and reports the failure to the client.
        }
    }

    private static Set<DN> dnValues(Attribute attribute, boolean optionalUid) {
        Set<DN> values = new LinkedHashSet<>();
        if (attribute == null) return values;
        for (String value : attribute.getValues()) {
            String dn = optionalUid ? OPTIONAL_UID.matcher(value).replaceFirst("") : value;
            try {
                values.add(new DN(dn));
            } catch (LDAPException e) {
                // Values that are not DNs never denote a member, as with the OpenLDAP overlay.
            }
        }
        return values;
    }

    private static LDAPException readOnly() {
        return new LDAPException(ResultCode.CONSTRAINT_VIOLATION,
                "memberOf is maintained by the server and cannot be written by clients");
    }

    private static AttributeTypeDefinition memberOfType() {
        try {
            return new AttributeTypeDefinition("( 1.2.840.113556.1.2.102 NAME 'memberOf'"
                    + " DESC 'Group that the entry belongs to' EQUALITY distinguishedNameMatch"
                    + " SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 NO-USER-MODIFICATION USAGE dSAOperation )");
        } catch (LDAPException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Entry memberOfSchemaEntry() {
        Entry entry = new Entry("cn=schema");
        entry.addAttribute("objectClass", "top", "ldapSubentry", "subschema");
        entry.addAttribute("attributeTypes", MEMBER_OF_TYPE.toString());
        return entry;
    }
}
