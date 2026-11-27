package io.github.wadahiro.keycloak.testframework.saml;

import java.util.List;
import java.util.Map;

/**
 * Identity data from a SAML response whose signature and protocol fields were validated.
 *
 * @param relayState the RelayState posted with the response: for SP-initiated login the value the client sent,
 *        for IdP-initiated login the value Keycloak sent, or {@code null} when the response had none
 */
public record SamlLoginResult(String nameId, String nameIdFormat, String sessionIndex, String responseId,
        Map<String, List<String>> attributes, String relayState) {
    public SamlLoginResult {
        attributes = attributes.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public SamlLoginResult(String nameId, String nameIdFormat, String sessionIndex, String responseId,
            Map<String, List<String>> attributes) {
        this(nameId, nameIdFormat, sessionIndex, responseId, attributes, null);
    }
}
