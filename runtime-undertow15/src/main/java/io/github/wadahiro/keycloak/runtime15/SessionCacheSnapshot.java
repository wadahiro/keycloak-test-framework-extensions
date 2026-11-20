package io.github.wadahiro.keycloak.runtime15;

import java.util.*;
import org.infinispan.Cache;
import org.infinispan.commons.marshall.Marshaller;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.*;

/** Serialize session entities; retaining live cache objects would let tests mutate the baseline. */
final class SessionCacheSnapshot {
    private final Map<String, byte[]> entries = new LinkedHashMap<>();
    SessionCacheSnapshot(KeycloakSessionFactory factory) throws Exception {
        KeycloakSession session = factory.create();
        try {
            InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
            for (String name : new String[]{"sessions", "clientSessions", "offlineSessions", "offlineClientSessions"}) {
                Cache<Object, Object> cache = provider.getCache(name);
                Marshaller marshaller = cache.getAdvancedCache().getComponentRegistry().getCacheMarshaller();
                entries.put(name, marshaller.objectToByteBuffer(new HashMap<>(cache)));
            }
        } finally { session.close(); }
    }
    @SuppressWarnings("unchecked")
    void restore(KeycloakSessionFactory factory) throws Exception {
        KeycloakSession session = factory.create();
        try {
            InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
            for (Map.Entry<String, byte[]> saved : entries.entrySet()) {
                Cache<Object, Object> cache = provider.getCache(saved.getKey());
                Marshaller marshaller = cache.getAdvancedCache().getComponentRegistry().getCacheMarshaller();
                cache.putAll((Map<Object, Object>) marshaller.objectFromByteBuffer(saved.getValue()));
            }
        } finally { session.close(); }
    }
}
