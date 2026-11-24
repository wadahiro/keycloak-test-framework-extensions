package io.github.wadahiro.keycloak.testframework.undertow;

import java.util.List;

import io.github.wadahiro.keycloak.testframework.snapshot.DisabledSnapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.InjectSnapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.Snapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.SnapshotActivation;
import io.github.wadahiro.keycloak.testframework.snapshot.SnapshotParticipant;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.testframework.TestFrameworkExtension;
import org.keycloak.testframework.admin.*;
import org.keycloak.testframework.annotations.*;
import org.keycloak.testframework.injection.*;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.server.*;

public final class UndertowExtension implements TestFrameworkExtension {
    @Override public List<Class<?>> alwaysEnabledValueTypes() { return List.of(Snapshot.class); }

    @Override public List<Supplier<?, ?>> suppliers() {
        return List.of(new ServerSupplier(), new AdminSupplier(), new SnapshotSupplier());
    }
    public static final class ServerSupplier implements Supplier<KeycloakServer, KeycloakIntegrationTest> {
        @Override public String getAlias() { return "undertow-process"; }
        @Override public LifeCycle getDefaultLifecycle() { return LifeCycle.GLOBAL; }
        @Override public int order() { return SupplierOrder.KEYCLOAK_SERVER; }
        @Override public KeycloakServer getValue(InstanceContext<KeycloakServer, KeycloakIntegrationTest> context) {
            rejectParallel(context);
            KeycloakServerConfig config = SupplierHelpers.getInstance(context.getAnnotation().config());
            KeycloakServerConfigBuilder builder = KeycloakServerConfigBuilder.startDev();
            config.configure(builder);
            UndertowProcessServer server = new UndertowProcessServer();
            server.start(builder, false);
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
    public static final class SnapshotSupplier implements Supplier<Snapshot, InjectSnapshot> {
        @Override public int order() { return 2000; }
        @Override public List<Dependency> getDependencies(RequestedInstance<Snapshot, InjectSnapshot> context) {
            return DependenciesBuilder.create(KeycloakServer.class).build();
        }
        @Override public Snapshot getValue(InstanceContext<Snapshot, InjectSnapshot> context) {
            rejectParallel(context);
            if (!SnapshotActivation.isEnabled(context.getRegistry().getCurrentContext().getRequiredTestClass())) {
                return new DisabledSnapshot();
            }
            KeycloakServer server = context.getDependency(KeycloakServer.class);
            if (!(server instanceof UndertowProcessServer undertow)) {
                throw new IllegalArgumentException("@InjectSnapshot requires kc.test.server=undertow-process");
            }
            return new UndertowSnapshot(undertow);
        }
        @Override public boolean compatible(InstanceContext<Snapshot, InjectSnapshot> a,
                RequestedInstance<Snapshot, InjectSnapshot> b) { return true; }
        @Override public void onBeforeEach(InstanceContext<Snapshot, InjectSnapshot> context) {
            if (context.getValue() instanceof DisabledSnapshot) return;
            for (InstanceContext<?, ?> resource : context.getRegistry().getDeployedInstances()) {
                if (resource.getLifeCycle() == LifeCycle.METHOD) {
                    throw new IllegalArgumentException("@InjectSnapshot requires CLASS resources; METHOD resource: "
                            + resource.getRequestedValueType().getSimpleName());
                }
                if (resource.getLifeCycle() == LifeCycle.GLOBAL
                        && (resource.getRequestedValueType() == ManagedRealm.class
                                || resource.getValue() instanceof SnapshotParticipant)) {
                    throw new IllegalArgumentException("@InjectSnapshot cannot be combined with GLOBAL stateful resource: "
                            + resource.getRequestedValueType().getSimpleName()
                            + ". Use CLASS lifecycle so each test class has an isolated baseline.");
                }
                if (resource.getValue() instanceof SnapshotParticipant participant) {
                    ((UndertowSnapshot) context.getValue()).addParticipant(participant);
                }
                // Release/restore before the Framework deletes any class-owned fixture.
                if (resource != context) resource.registerDependent(context);
            }
        }
        @Override public void close(InstanceContext<Snapshot, InjectSnapshot> context) {
            if (context.getValue() instanceof UndertowSnapshot snapshot) snapshot.close();
        }
    }
    private static void rejectParallel(InstanceContext<?, ?> context) {
        if (context.getRegistry().getCurrentContext().getConfigurationParameter("junit.jupiter.execution.parallel.enabled")
                .map(Boolean::parseBoolean).orElse(false)) {
            throw new IllegalArgumentException("Undertow H2 reset does not support JUnit parallel execution");
        }
    }
}
