package io.github.wadahiro.keycloak.runtimemodern;

import java.net.*;
import java.nio.file.*;
import javax.net.ssl.*;
import ${servlet.namespace}.servlet.DispatcherType;
import org.keycloak.models.*;
import org.keycloak.services.managers.ApplianceBootstrap;
import org.keycloak.services.resources.KeycloakApplication;
import org.keycloak.util.JsonSerialization;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.undertow.Undertow;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DefaultServletConfig;
import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.plugins.server.servlet.ResteasyContextParameters;

public final class EmbeddedRuntime {
    private UndertowJaxrsServer server;
    private boolean stopped;
    public String start(String directory) throws Exception {
        EmbeddedPlatform.directory = Path.of(directory);
        System.setProperty("jboss.server.data.dir", directory);
        System.setProperty("jboss.server.config.dir", directory);
        try (var source = getClass().getResourceAsStream("/META-INF/keycloak-server.json")) {
            ObjectNode json = (ObjectNode) JsonSerialization.mapper.readTree(source);
            ObjectNode jpa = (ObjectNode) json.path("connectionsJpa").path("default");
            jpa.put("url", System.getProperty("kctf.jdbc")); jpa.put("driver", "org.h2.Driver"); jpa.put("user", "sa"); jpa.put("password", "");
            json.putObject("timer").put("provider", "snapshot-barrier");
            JsonSerialization.mapper.writeValue(Path.of(directory,"keycloak-server.json").toFile(), json);
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) { port=socket.getLocalPort(); }
        int major = Integer.parseInt(org.keycloak.common.Version.VERSION.substring(0, org.keycloak.common.Version.VERSION.indexOf('.')));
        java.lang.reflect.Method profileInstance = org.keycloak.common.Profile.class.getDeclaredMethod("getInstance");
        profileInstance.setAccessible(true);
        if (profileInstance.invoke(null) == null) org.keycloak.common.Profile.class.getMethod("defaults").invoke(null);
        var deployment = new ResteasyDeploymentImpl();
        deployment.setApplicationClass(major >= 25 ? "org.keycloak.services.resteasy.ResteasyKeycloakApplication" : KeycloakApplication.class.getName());
        deployment.setProperty(ResteasyContextParameters.RESTEASY_DISABLE_HTML_SANITIZER, true);
        server = new UndertowJaxrsServer();
        try {
            server.start(Undertow.builder().addHttpListener(port,"127.0.0.1").setIoThreads(2).setWorkerThreads(8));
            var info = server.undertowDeployment(deployment, "").setClassLoader(getClass().getClassLoader())
                    .setContextPath("/auth").setDeploymentName("Keycloak").setDefaultEncoding("UTF-8")
                    .setDefaultServletConfig(new DefaultServletConfig(true));
            info.addFilter(Servlets.filter("SnapshotBarrier", QuiescentFilter.class).setAsyncSupported(true));
            info.addFilterUrlMapping("SnapshotBarrier", "/*", DispatcherType.REQUEST);
            info.addFilter(Servlets.filter("SessionFilter", UndertowRequestFilter.class).setAsyncSupported(true));
            info.addFilterUrlMapping("SessionFilter", "/*", DispatcherType.REQUEST);
            server.deploy(info);
            KeycloakSession session=KeycloakApplication.getSessionFactory().create();
            var tx=session.getTransactionManager();
            KeycloakSession previous=SessionContext.set(session);
            try {
                tx.begin(); var bootstrap=new ApplianceBootstrap(session);
                String user=System.getenv().getOrDefault("KCTF_ADMIN_USERNAME","admin"), password=System.getenv().getOrDefault("KCTF_ADMIN_PASSWORD","admin");
                try { ApplianceBootstrap.class.getMethod("createMasterRealmUser",String.class,String.class).invoke(bootstrap,user,password); }
                catch (NoSuchMethodException newer) { ApplianceBootstrap.class.getMethod("createMasterRealmUser",String.class,String.class,boolean.class).invoke(bootstrap,user,password,false); }
                tx.commit();
            } catch (Exception | Error failure) { if (tx.isActive()) tx.rollback(); throw failure; }
            finally { SessionContext.set(previous); session.close(); }
            return "http://localhost:"+port+"/auth";
        } catch (Throwable failure) { try { stop(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); } throw failure; }
    }
    public Object sessionFactory() { return KeycloakApplication.getSessionFactory(); }
    public void stop() throws Exception {
        if (stopped) return; stopped=true;
        if (server != null) { server.stop(); server=null; }
        if (EmbeddedPlatform.shutdown != null) { var shutdown=EmbeddedPlatform.shutdown; EmbeddedPlatform.shutdown=null; shutdown.run(); }
        else if (KeycloakApplication.getSessionFactory()!=null) KeycloakApplication.getSessionFactory().close();
        try { Class.forName("io.reactivex.rxjava3.schedulers.Schedulers").getMethod("shutdown").invoke(null); } catch (ClassNotFoundException ignored) { }
    }
}
