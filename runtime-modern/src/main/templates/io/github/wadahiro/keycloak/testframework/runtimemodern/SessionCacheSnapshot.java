package io.github.wadahiro.keycloak.testframework.runtimemodern;

import java.util.*;
import org.infinispan.Cache;
import org.infinispan.commons.marshall.Marshaller;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.*;

/** Serialize session entities; retaining live cache objects would let tests mutate the baseline. */
final class SessionCacheSnapshot {
    private final Map<String, byte[]> entries = new LinkedHashMap<>();
    SessionCacheSnapshot(KeycloakSessionFactory factory) throws Exception {
        // Keycloak 26 persists sessions in JPA; the DB snapshot is authoritative.
        // Eviction on restore makes its persistent provider reload the baseline.
        if (Integer.parseInt(org.keycloak.common.Version.VERSION.split("[.]")[0]) >= 26) return;
        KeycloakSession session = factory.create();
        KeycloakSession previous=SessionContext.set(session);
        try {
            InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
            for (String name : new String[]{"sessions", "clientSessions", "offlineSessions", "offlineClientSessions"}) {
                Cache<Object, Object> cache = provider.getCache(name);
                Marshaller marshaller = marshaller(cache);
                entries.put(name, marshaller.objectToByteBuffer(new HashMap<>(cache)));
            }
        } finally { SessionContext.set(previous); session.close(); }
    }
    private static Marshaller marshaller(Cache<?, ?> cache) throws Exception {
        Object manager = cache.getCacheManager();
        Object configuration = manager.getClass().getMethod("getCacheManagerConfiguration").invoke(manager);
        Object serialization = configuration.getClass().getMethod("serialization").invoke(configuration);
        Marshaller result = (Marshaller) serialization.getClass().getMethod("marshaller").invoke(serialization);
        if (result == null) throw new IllegalStateException("No configured Infinispan session marshaller");
        return result;
    }

    @SuppressWarnings("unchecked")
    void restore(KeycloakSessionFactory factory) throws Exception {
        KeycloakSession session = factory.create();
        KeycloakSession previous=SessionContext.set(session);
        try {
            InfinispanConnectionProvider provider = session.getProvider(InfinispanConnectionProvider.class);
            for (Map.Entry<String, byte[]> saved : entries.entrySet()) {
                Cache<Object, Object> cache = provider.getCache(saved.getKey());
                Marshaller marshaller = marshaller(cache);
                cache.putAll((Map<Object, Object>) marshaller.objectFromByteBuffer(saved.getValue()));
            }
        } finally { SessionContext.set(previous); session.close(); }
    }
}
