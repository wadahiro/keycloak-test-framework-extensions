package io.github.wadahiro.keycloak.runtimemodern;

import java.io.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.*;

/** Private parent-process protocol; no reset endpoint is exposed over HTTP. */
public final class RuntimeMain {
    private static final String PREFIX = "KCTF_CONTROL ";
    private static final AtomicBoolean stopped = new AtomicBoolean();
    private static EmbeddedRuntime runtime;
    private static H2DatabaseSnapshot snapshot;
    private static Connection db;
    private static SessionCacheSnapshot sessions;
    private static String lease;

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != Integer.parseInt(System.getenv("KCTF_RUNTIME_JAVA"))) throw new IllegalArgumentException("Unexpected server JDK");
        String jdbc = "jdbc:h2:mem:kctf_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=0;LOCK_TIMEOUT=5000";
        System.setProperty("kctf.jdbc", jdbc);
        runtime = new EmbeddedRuntime();
        Runtime.getRuntime().addShutdownHook(new Thread(RuntimeMain::stop, "keycloak-runtime-stop"));
        try {
            String url = runtime.start(Path.of(args[0]).toString());
            db = DriverManager.getConnection(jdbc, "sa", "");
            System.out.println(PREFIX + "READY " + url);
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                for (String line; (line = input.readLine()) != null;) {
                    if (line.equals("STOP")) break;
                    String[] fields = line.split(" ", -1);
                    String id = fields.length == 2 ? fields[1] : "invalid";
                    try {
                        long started = System.nanoTime();
                        if (fields.length != 2) throw new IllegalArgumentException("Invalid control request");
                        UUID.fromString(id);
                        RequestGate.exclusive(() -> { command(fields[0], id); return null; });
                        System.out.println(PREFIX + "OK " + fields[0] + " " + id + " " + (System.nanoTime() - started) / 1e6);
                    } catch (Throwable failure) {
                        RequestGate.poisoned = true;
                        failure.printStackTrace(System.err);
                        System.out.println(PREFIX + "ERROR " + fields[0] + " " + id);
                        System.out.flush();
                        break;
                    }
                    System.out.flush();
                }
            }
        } finally { stop(); }
    }

    private static void command(String op, String id) throws Exception {
        if (op.equals("CAPTURE")) {
            if (snapshot != null) throw new IllegalStateException("Only one snapshot owner is allowed");
            snapshot = new H2DatabaseSnapshot(db);
            sessions = new SessionCacheSnapshot((KeycloakSessionFactory) runtime.sessionFactory());
            lease = id;
        } else {
            if (snapshot == null || !id.equals(lease)) throw new IllegalStateException("Snapshot lease does not match");
            if (op.equals("RESTORE")) { snapshot.restore(); clearCaches(); sessions.restore((KeycloakSessionFactory) runtime.sessionFactory()); }
            else if (op.equals("RELEASE")) { snapshot.close(); snapshot = null; sessions = null; lease = null; }
            else throw new IllegalArgumentException("Unknown control command " + op);
        }
    }

    private static void clearCaches() {
        KeycloakSessionFactory factory = (KeycloakSessionFactory) runtime.sessionFactory();
        KeycloakSession session = factory.create();
        KeycloakTransaction tx = session.getTransactionManager();
        KeycloakSession previous=SessionContext.set(session);
        try {
            tx.begin();
            session.getProvider(JpaConnectionProvider.class).getEntityManager().getEntityManagerFactory().getCache().evictAll();
            InfinispanConnectionProvider caches = session.getProvider(InfinispanConnectionProvider.class);
            for (String name : "${runtime.cache.names}".split(",")) caches.getCache(name).clear();
            tx.commit();
        } catch (RuntimeException | Error failure) {
            if (tx.isActive()) tx.rollback();
            throw failure;
        } finally { SessionContext.set(previous); session.close(); }
    }

    private static void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        try {
            if (snapshot != null) snapshot.close();
            if (db != null) db.close();
        } catch (Exception failure) { failure.printStackTrace(System.err); }
        finally {
            try { if (runtime != null) runtime.stop(); }
            catch (Exception failure) { failure.printStackTrace(System.err); }
        }
    }
}
