package io.github.wadahiro.keycloak.testframework.broker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SamlIdentity(String nameId, String nameIdFormat, Map<String, List<String>> attributes) {
    public SamlIdentity {
        if (nameId == null || nameId.isBlank()) throw new IllegalArgumentException("nameId is required");
        nameIdFormat = nameIdFormat == null ? "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified" : nameIdFormat;
        Map<String, List<String>> copy = new LinkedHashMap<>();
        attributes.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        attributes = Map.copyOf(copy);
    }

    public static SamlIdentity nameId(String value) {
        return new SamlIdentity(value, null, Map.of());
    }

    public SamlIdentity attribute(String name, String value) {
        Map<String, List<String>> next = new LinkedHashMap<>(attributes);
        next.put(name, List.of(value));
        return new SamlIdentity(nameId, nameIdFormat, next);
    }

    public SamlIdentity multiValueAttribute(String name, List<String> values) {
        Map<String, List<String>> next = new LinkedHashMap<>(attributes);
        next.put(name, List.copyOf(values));
        return new SamlIdentity(nameId, nameIdFormat, next);
    }
}
