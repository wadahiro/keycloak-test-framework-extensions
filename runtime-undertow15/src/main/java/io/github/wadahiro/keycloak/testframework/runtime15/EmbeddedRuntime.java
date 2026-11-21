package io.github.wadahiro.keycloak.testframework.runtime15;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import javax.servlet.DispatcherType;

import org.keycloak.Config;
import org.keycloak.common.Version;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.ApplianceBootstrap;
import org.keycloak.services.resources.KeycloakApplication;
import org.keycloak.services.util.JsonConfigProvider;
import org.keycloak.util.JsonSerialization;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.undertow.Undertow;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DefaultServletConfig;
import io.undertow.servlet.api.DeploymentInfo;
import org.jboss.resteasy.plugins.server.servlet.ResteasyContextParameters;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.spi.ResteasyDeployment;

/** Minimal adaptation of v15's testsuite/utils KeycloakServer.start/stop. */
public class EmbeddedRuntime {
    private UndertowJaxrsServer server;
    private KeycloakSessionFactory sessionFactory;
    private int port;

    public String start(String workDirectory) throws Exception {
        EmbeddedPlatform.directory = Path.of(workDirectory);
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        ResteasyDeployment deployment = new ResteasyDeployment();
        deployment.setApplicationClass(Application.class.getName());
        deployment.setProperty(ResteasyContextParameters.RESTEASY_DISABLE_HTML_SANITIZER, true);
        server = new UndertowJaxrsServer();
        try {
            server.start(Undertow.builder().addHttpListener(port, "127.0.0.1").setIoThreads(2).setWorkerThreads(8));
            DeploymentInfo info = server.undertowDeployment(deployment, "")
                    .setClassLoader(getClass().getClassLoader()).setContextPath("/auth")
                    .setDeploymentName("Keycloak15Embedded").setDefaultEncoding("UTF-8")
                    .setDefaultServletConfig(new DefaultServletConfig(true));
            info.addFilter(Servlets.filter("SnapshotBarrier", QuiescentFilter.class));
            info.addFilterUrlMapping("SnapshotBarrier", "/*", DispatcherType.REQUEST);
            info.addFilter(Servlets.filter("SessionFilter", UndertowRequestFilter.class,
                    () -> new io.undertow.servlet.util.ImmediateInstanceHandle<>(new UndertowRequestFilter(null)))
                    .setAsyncSupported(false));
            info.addFilterUrlMapping("SessionFilter", "/*", DispatcherType.REQUEST);
            server.deploy(info);
            sessionFactory = KeycloakApplication.getSessionFactory();
            KeycloakSession session = sessionFactory.create();
            try {
                session.getTransactionManager().begin();
                new ApplianceBootstrap(session).createMasterRealmUser(System.getenv().getOrDefault("KCTF_ADMIN_USERNAME", "admin"), System.getenv().getOrDefault("KCTF_ADMIN_PASSWORD", "admin"));
                session.getTransactionManager().commit();
            } finally {
                session.close();
            }
            return "http://127.0.0.1:" + port + "/auth";
        } catch (Throwable failure) {
            try {
                stop();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public Object sessionFactory() {
        return sessionFactory;
    }

    public void stop() throws Exception {
        try {
            if (server != null) {
                server.stop();
                server = null;
            }
        } finally {
            try {
                if (EmbeddedPlatform.shutdown != null) {
                    Runnable shutdown = EmbeddedPlatform.shutdown;
                    EmbeddedPlatform.shutdown = null;
                    shutdown.run();
                } else if (KeycloakApplication.getSessionFactory() != null) {
                    // Constructor failures can happen before the platform shutdown callback is registered.
                    KeycloakApplication.getSessionFactory().close();
                }
                sessionFactory = null;
            } finally {
                // These static RxJava pools outlive Infinispan's cache manager unless explicitly shut down.
                io.reactivex.rxjava3.schedulers.Schedulers.shutdown();
                io.reactivex.rxjava3.internal.schedulers.SchedulerPoolFactory.shutdown();
                // DriverManager is JVM-global; release drivers registered by this isolated runtime.
                Enumeration<Driver> drivers = DriverManager.getDrivers();
                while (drivers.hasMoreElements()) {
                    Driver driver = drivers.nextElement();
                    if (driver.getClass().getClassLoader() == getClass().getClassLoader()) {
                        DriverManager.deregisterDriver(driver);
                    }
                }
            }
        }
    }

    public static class Application extends KeycloakApplication {
        @Override
        protected void loadConfig() {
            try (var stream = getClass().getResourceAsStream("/META-INF/keycloak-server.json")) {
                ObjectNode json = (ObjectNode) JsonSerialization.mapper.readTree(stream);
                ObjectNode jpa = (ObjectNode) json.path("connectionsJpa").path("default");
                jpa.put("url", System.getProperty("kctf.jdbc"));
                jpa.put("driver", "org.h2.Driver");
                jpa.put("user", "sa");
                jpa.put("password", "");
                json.putObject("timer").put("provider", "snapshot-barrier");
                Config.init(new JsonConfigProvider(json, new Properties()));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot configure isolated Keycloak", e);
            }
        }
    }
}
