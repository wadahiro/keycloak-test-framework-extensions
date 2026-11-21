package io.github.wadahiro.keycloak.broker;

import java.util.List;
import java.util.Optional;

public record BrokeredUser(String id, String username, List<BrokerIdentityLink> identityLinks) {
    public BrokeredUser {
        identityLinks = List.copyOf(identityLinks);
    }

    public Optional<BrokerIdentityLink> identityProvider(String alias) {
        return identityLinks.stream().filter(link -> link.identityProvider().equals(alias)).findFirst();
    }
}
