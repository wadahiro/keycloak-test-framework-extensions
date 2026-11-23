package io.github.wadahiro.keycloak.testframework.quarkus;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.quarkus.bootstrap.app.RunningQuarkusApplication;

/** Performs database and Keycloak cache operations inside the bootstrapped Quarkus application. */
final class QuarkusSnapshotControl implements AutoCloseable {
    private static final String[] CACHES = {"realms", "realmRevisions", "users", "userRevisions", "authorization",
            "authorizationRevisions", "sessions", "clientSessions", "offlineSessions", "offlineClientSessions",
            "loginFailures", "authenticationSessions", "actionTokens", "keys"};

    private final RunningQuarkusApplication application;
    private final ClassLoader appClassLoader;
    private final QuarkusRequestGate requestGate;
    private final boolean timerGateEnabled;
    private H2DataSnapshot database;
    private Map<String, byte[]> sessionCaches;

    QuarkusSnapshotControl(RunningQuarkusApplication application, QuarkusRequestGate requestGate, boolean timerGateEnabled) {
        this.application = Objects.requireNonNull(application);
        this.appClassLoader = application.getClassLoader();
        this.requestGate = Objects.requireNonNull(requestGate);
        this.timerGateEnabled = timerGateEnabled;
    }

    synchronized void capture() throws Exception {
        requestGate.exclusive(() -> {
            captureExclusive();
            return null;
        });
    }

    private void captureExclusive() throws Exception {
        if (database != null) throw new IllegalStateException("Only one snapshot owner is supported");
        Object sessionFactory = sessionFactory();
        Class<?> jpaProviderClass = appClassLoader.loadClass("org.keycloak.connections.jpa.JpaConnectionProvider");
        Object jpaFactory = invoke(sessionFactory, "getProviderFactory", jpaProviderClass);
        Connection connection = (Connection) invoke(jpaFactory, "getConnection");
        try {
            database = new H2DataSnapshot(connection);
            sessionCaches = captureSessionCaches(sessionFactory);
        } catch (Exception failure) {
            if (database != null) database.close(); else connection.close();
            database = null;
            throw failure;
        }
    }

    synchronized void restore() throws Exception {
        requestGate.exclusive(() -> {
            restoreExclusive();
            return null;
        });
    }

    private void restoreExclusive() throws Exception {
        if (database == null) throw new IllegalStateException("No H2 snapshot has been captured");
        database.restore();
        Object sessionFactory = sessionFactory();
        clearCaches(sessionFactory);
        restoreSessionCaches(sessionFactory, sessionCaches);
    }

    synchronized void release() {
        try {
            requestGate.exclusive(() -> {
                releaseExclusive();
                return null;
            });
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to release H2 snapshot", failure);
        }
    }

    private void releaseExclusive() {
        if (database == null) throw new IllegalStateException("No H2 snapshot has been captured");
        database.close();
        database = null;
        sessionCaches = null;
    }

    private Object sessionFactory() throws Exception {
        Class<?> quarkusFactory = appClassLoader.loadClass(
                "org.keycloak.quarkus.runtime.integration.QuarkusKeycloakSessionFactory");
        try {
            // Newer Keycloak exposes the concrete factory as a CDI bean.
            return application.instance(quarkusFactory);
        } catch (RuntimeException unavailable) {
            // Keycloak 25 keeps the factory as a static singleton; newer releases expose it as a CDI bean.
            return quarkusFactory.getMethod("getInstance").invoke(null);
        }
    }

    private Map<String, byte[]> captureSessionCaches(Object sessionFactory) throws Exception {
        Class<?> providerClass = appClassLoader.loadClass("org.keycloak.connections.infinispan.InfinispanConnectionProvider");
        Object session = invoke(sessionFactory, "create");
        try {
            if (timerGateEnabled) {
                Class<?> timerClass = appClassLoader.loadClass("org.keycloak.timer.TimerProvider");
                Object timer = invoke(session, "getProvider", timerClass);
                if (timer == null || !timer.getClass().getName().startsWith("io.github.wadahiro.keycloak.testframework.quarkus.GatedTimerFactory$")) {
                    throw new IllegalStateException("The snapshot-gated Keycloak TimerProvider is not active");
                }
            }
            Object provider = invoke(session, "getProvider", providerClass);
            Map<String, byte[]> result = new LinkedHashMap<>();
            for (String name : new String[] {"sessions", "clientSessions", "offlineSessions", "offlineClientSessions"}) {
                Object cache = invoke(provider, "getCache", name);
                Object marshaller = marshaller(cache);
                byte[] serialized = (byte[]) invoke(marshaller, "objectToByteBuffer", new HashMap<>((Map<?, ?>) cache));
                result.put(name, serialized);
            }
            return result;
        } finally {
            invoke(session, "close");
        }
    }

    @SuppressWarnings("unchecked")
    private void restoreSessionCaches(Object sessionFactory, Map<String, byte[]> cacheData) throws Exception {
        Class<?> providerClass = appClassLoader.loadClass("org.keycloak.connections.infinispan.InfinispanConnectionProvider");
        Object session = invoke(sessionFactory, "create");
        try {
            Object provider = invoke(session, "getProvider", providerClass);
            for (Map.Entry<String, byte[]> item : cacheData.entrySet()) {
                Object cache = invoke(provider, "getCache", item.getKey());
                Object entries = invoke(marshaller(cache), "objectFromByteBuffer", item.getValue());
                invoke(cache, "putAll", (Map<Object, Object>) entries);
            }
        } finally {
            invoke(session, "close");
        }
    }

    private void clearCaches(Object sessionFactory) throws Exception {
        Class<?> providerClass = appClassLoader.loadClass("org.keycloak.connections.infinispan.InfinispanConnectionProvider");
        Class<?> jpaProviderClass = appClassLoader.loadClass("org.keycloak.connections.jpa.JpaConnectionProvider");
        Object session = invoke(sessionFactory, "create");
        Object transaction = invoke(session, "getTransactionManager");
        try {
            invoke(transaction, "begin");
            Object jpa = invoke(session, "getProvider", jpaProviderClass);
            Object entityManager = invoke(jpa, "getEntityManager");
            Object entityManagerFactory = invoke(entityManager, "getEntityManagerFactory");
            Object cache = invoke(entityManagerFactory, "getCache");
            invoke(cache, "evictAll");
            Object infinispan = invoke(session, "getProvider", providerClass);
            for (String name : CACHES) {
                Object keycloakCache = cacheIfPresent(infinispan, name);
                if (keycloakCache != null) invoke(keycloakCache, "clear");
            }
            invoke(transaction, "commit");
        } catch (Exception | Error failure) {
            try { if ((boolean) invoke(transaction, "isActive")) invoke(transaction, "rollback"); }
            catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally {
            invoke(session, "close");
        }
    }

    private Object marshaller(Object cache) throws Exception {
        Object advancedCache = invoke(cache, "getAdvancedCache");
        Object componentRegistry = invoke(advancedCache, "getComponentRegistry");
        return invoke(componentRegistry, "getCacheMarshaller");
    }

    private Object cacheIfPresent(Object provider, String name) throws Exception {
        try {
            return invoke(provider, "getCache", name, false);
        } catch (NoSuchMethodException olderKeycloak) {
            return invoke(provider, "getCache", name);
        }
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Method method = null;
        for (Method candidate : target.getClass().getMethods()) {
            if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null && !wrap(candidate.getParameterTypes()[i]).isAssignableFrom(args[i].getClass())) {
                    matches = false;
                    break;
                }
            }
            if (matches) { method = candidate; break; }
        }
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        return type;
    }

    @Override public synchronized void close() {
        if (database != null) release();
    }
}
