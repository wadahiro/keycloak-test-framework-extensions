package io.github.wadahiro.keycloak.saml;

import java.util.List;
import java.util.Map;

public record SamlLoginResult(String nameId, String nameIdFormat, String sessionIndex, String responseId,
        Map<String, List<String>> attributes) {
    public SamlLoginResult {
        attributes = attributes.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}
