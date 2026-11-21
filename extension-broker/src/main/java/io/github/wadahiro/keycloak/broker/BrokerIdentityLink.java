package io.github.wadahiro.keycloak.broker;

public record BrokerIdentityLink(String identityProvider, String externalUserId, String externalUsername) {
}
