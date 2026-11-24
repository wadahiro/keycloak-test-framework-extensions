package io.github.wadahiro.keycloak.testframework.quarkus;

import java.util.List;

import io.github.wadahiro.keycloak.testframework.snapshot.InjectSnapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.Snapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.SnapshotParticipant;
import org.jboss.logging.Logger;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.testframework.TestFrameworkExtension;
import org.keycloak.testframework.admin.AdminClientFactory;
import org.keycloak.testframework.admin.AdminClientSupplier;
import org.keycloak.testframework.annotations.InjectAdminClient;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.injection.RequestedInstance;
import org.keycloak.testframework.injection.Supplier;
import org.keycloak.testframework.injection.SupplierOrder;
import org.keycloak.testframework.server.KeycloakServer;
import org.keycloak.testframework.server.AbstractKeycloakServerSupplier;
import org.keycloak.testframework.realm.ManagedRealm;

public final class QuarkusTestFrameworkExtension implements TestFrameworkExtension {
    @Override
    public List<Supplier<?, ?>> suppliers() {
        return List.of(new ServerSupplier(), new AdminSupplier(), new SnapshotSupplier());
    }

    public static final class ServerSupplier extends AbstractKeycloakServerSupplier {
        @Override public String getAlias() { return "quarkus-process"; }
        @Override public KeycloakServer getServer() { return new QuarkusProcessServer(); }
        @Override public boolean requiresDatabase() { return true; }
        @Override public Logger getLogger() { return Logger.getLogger(ServerSupplier.class); }
    }

    public static final class AdminSupplier extends AdminClientSupplier {
        @Override public String getAlias() { return "quarkus-admin"; }

        @Override
        public Keycloak getValue(InstanceContext<Keycloak, InjectAdminClient> context) {
            String version = selectedVersion();
            if (Integer.parseInt(version.split("[.]", 2)[0]) >= 26
                    || context.getAnnotation().mode() != InjectAdminClient.Mode.BOOTSTRAP) {
                return super.getValue(context);
            }
            return context.getDependency(AdminClientFactory.class).create().realm("master").clientId("admin-cli")
                    .grantType("password")
                    .username(org.keycloak.testframework.config.Config.getAdminUsername())
                    .password(org.keycloak.testframework.config.Config.getAdminPassword())
                    .build();
        }
    }

    public static final class SnapshotSupplier implements Supplier<Snapshot, InjectSnapshot> {
        @Override public int order() { return 2000; }

        @Override
        public List<org.keycloak.testframework.injection.Dependency> getDependencies(
                org.keycloak.testframework.injection.RequestedInstance<Snapshot, InjectSnapshot> context) {
            return org.keycloak.testframework.injection.DependenciesBuilder.create(KeycloakServer.class).build();
        }

        @Override
        public Snapshot getValue(InstanceContext<Snapshot, InjectSnapshot> context) {
            rejectParallel(context);
            KeycloakServer server = context.getDependency(KeycloakServer.class);
            if (!(server instanceof QuarkusProcessServer quarkus)) {
                throw new IllegalArgumentException("@InjectSnapshot requires kc.test.server=quarkus-process");
            }
            return new QuarkusSnapshot(quarkus);
        }

        @Override public boolean compatible(InstanceContext<Snapshot, InjectSnapshot> a,
                RequestedInstance<Snapshot, InjectSnapshot> b) { return true; }

        @Override
        public void onBeforeEach(InstanceContext<Snapshot, InjectSnapshot> context) {
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
                    ((QuarkusSnapshot) context.getValue()).addParticipant(participant);
                }
                if (resource != context) resource.registerDependent(context);
            }
            ((QuarkusSnapshot) context.getValue()).initialize();
        }

        @Override public void close(InstanceContext<Snapshot, InjectSnapshot> context) {
            ((QuarkusSnapshot) context.getValue()).close();
        }
    }

    private static String selectedVersion() {
        try {
            return RuntimeDescriptor.select(org.keycloak.testframework.config.Config.get("kc.test.quarkus.version", null, String.class)).version();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Unable to read the prepared Keycloak runtime descriptor", failure);
        }
    }

    private static void rejectParallel(InstanceContext<?, ?> context) {
        if (context.getRegistry().getCurrentContext().getConfigurationParameter("junit.jupiter.execution.parallel.enabled")
                .map(Boolean::parseBoolean).orElse(false)) {
            throw new IllegalArgumentException("Quarkus H2 reset does not support JUnit parallel execution");
        }
    }
}
