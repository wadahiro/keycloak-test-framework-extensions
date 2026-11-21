package io.github.wadahiro.keycloak.testframework.broker;

import java.util.List;

import org.keycloak.representations.idm.FederatedIdentityRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.realm.ManagedRealm;

/** Read-only queries for the federated identity links created by broker flows. */
public final class BrokerIdentityLinks {
    private final ManagedRealm realm;

    private BrokerIdentityLinks(ManagedRealm realm) {
        this.realm = realm;
    }

    public static BrokerIdentityLinks forRealm(ManagedRealm realm) {
        return new BrokerIdentityLinks(realm);
    }

    public BrokeredUser findByUsername(String username) {
        List<UserRepresentation> users = realm.admin().users().search(username).stream()
                .filter(user -> username.equals(user.getUsername())).toList();
        if (users.isEmpty()) throw new IllegalArgumentException("No realm user with username '" + username + "'");
        if (users.size() > 1) throw new IllegalStateException("More than one realm user with username '" + username + "'");
        return from(users.get(0));
    }

    public BrokeredUser findById(String userId) {
        UserRepresentation user = realm.admin().users().get(userId).toRepresentation();
        return from(user);
    }

    private BrokeredUser from(UserRepresentation user) {
        List<FederatedIdentityRepresentation> identities = realm.admin().users().get(user.getId()).getFederatedIdentity();
        List<BrokerIdentityLink> links = identities == null ? List.of() : identities.stream()
                .map(BrokerIdentityLinks::toLink).toList();
        return new BrokeredUser(user.getId(), user.getUsername(), links);
    }

    private static BrokerIdentityLink toLink(FederatedIdentityRepresentation identity) {
        return new BrokerIdentityLink(identity.getIdentityProvider(), identity.getUserId(), identity.getUserName());
    }
}
