package io.github.wadahiro.keycloak.testframework.undertow;

import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.testframework.TestFrameworkExtension;
import org.keycloak.testframework.admin.*;
import org.keycloak.testframework.annotations.*;
import org.keycloak.testframework.injection.*;
import org.keycloak.testframework.server.*;

public final class UndertowExtension implements TestFrameworkExtension {
    @Override public List<Supplier<?, ?>> suppliers() {
        return List.of(new ServerSupplier(), new AdminSupplier(), new SnapshotSupplier());
    }
    public static final class ServerSupplier implements Supplier<KeycloakServer, KeycloakIntegrationTest> {
        @Override public String getAlias() { return "undertow-process"; }
        @Override public LifeCycle getDefaultLifecycle() { return LifeCycle.GLOBAL; }
        @Override public int order() { return SupplierOrder.KEYCLOAK_SERVER; }
        @Override public KeycloakServer getValue(InstanceContext<KeycloakServer, KeycloakIntegrationTest> context) {
            if (context.getAnnotation().config() != DefaultKeycloakServerConfig.class) {
                throw new IllegalArgumentException("Modern server configuration is unsupported by Undertow 15.0.2");
            }
            rejectParallel(context);
            UndertowProcessServer server = new UndertowProcessServer();
            server.start(null, false);
            return server;
        }
        @Override public boolean compatible(InstanceContext<KeycloakServer, KeycloakIntegrationTest> a,
                RequestedInstance<KeycloakServer, KeycloakIntegrationTest> b) { return a.getAnnotation().config() == b.getAnnotation().config(); }
        @Override public void close(InstanceContext<KeycloakServer, KeycloakIntegrationTest> context) { context.getValue().stop(); }
    }
    public static final class AdminSupplier extends AdminClientSupplier {
        @Override public String getAlias() { return "undertow-admin"; }
        @Override public Keycloak getValue(InstanceContext<Keycloak, InjectAdminClient> context) {
            if (context.getAnnotation().mode() != InjectAdminClient.Mode.BOOTSTRAP) return super.getValue(context);
            return context.getDependency(AdminClientFactory.class).create().realm("master").clientId("admin-cli")
                    .grantType("password").username(UndertowProcessServer.value("admin.username", "admin"))
                    .password(UndertowProcessServer.value("admin.password", "admin")).build();
        }
    }
    public static final class SnapshotSupplier implements Supplier<H2Snapshot, InjectH2Snapshot> {
        @Override public int order() { return 2000; }
        @Override public List<Dependency> getDependencies(RequestedInstance<H2Snapshot, InjectH2Snapshot> context) {
            return DependenciesBuilder.create(KeycloakServer.class).build();
        }
        @Override public H2Snapshot getValue(InstanceContext<H2Snapshot, InjectH2Snapshot> context) {
            rejectParallel(context);
            KeycloakServer server = context.getDependency(KeycloakServer.class);
            if (!(server instanceof UndertowProcessServer undertow)) {
                throw new IllegalArgumentException("@InjectH2Snapshot requires kc.test.server=undertow-process");
            }
            return new H2Snapshot(undertow);
        }
        @Override public boolean compatible(InstanceContext<H2Snapshot, InjectH2Snapshot> a,
                RequestedInstance<H2Snapshot, InjectH2Snapshot> b) { return true; }
        @Override public void onBeforeEach(InstanceContext<H2Snapshot, InjectH2Snapshot> context) {
            for (InstanceContext<?, ?> resource : context.getRegistry().getDeployedInstances()) {
                if (resource.getLifeCycle() == LifeCycle.METHOD) {
                    throw new IllegalArgumentException("H2 reset requires CLASS/GLOBAL resources; METHOD resource: "
                            + resource.getRequestedValueType().getSimpleName());
                }
                if (resource.getValue() instanceof io.github.wadahiro.keycloak.testframework.snapshot.H2SnapshotParticipant participant) {
                    context.getValue().addParticipant(participant);
                }
                // Release/restore before the Framework deletes any class-owned fixture.
                if (resource != context) resource.registerDependent(context);
            }
            context.getValue().initialize();
        }
        @Override public void close(InstanceContext<H2Snapshot, InjectH2Snapshot> context) { context.getValue().close(); }
    }
    private static void rejectParallel(InstanceContext<?, ?> context) {
        if (context.getRegistry().getCurrentContext().getConfigurationParameter("junit.jupiter.execution.parallel.enabled")
                .map(Boolean::parseBoolean).orElse(false)) {
            throw new IllegalArgumentException("Undertow H2 reset does not support JUnit parallel execution");
        }
    }
}
