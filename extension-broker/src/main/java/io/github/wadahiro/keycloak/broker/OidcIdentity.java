package io.github.wadahiro.keycloak.broker;

import java.util.LinkedHashMap;
import java.util.Map;

public record OidcIdentity(String subject, Map<String, Object> claims) {
    public OidcIdentity {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject is required");
        claims = Map.copyOf(claims);
    }

    public static OidcIdentity subject(String subject) {
        return new OidcIdentity(subject, Map.of());
    }

    public OidcIdentity claim(String name, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(claims);
        next.put(name, value);
        return new OidcIdentity(subject, next);
    }
}
