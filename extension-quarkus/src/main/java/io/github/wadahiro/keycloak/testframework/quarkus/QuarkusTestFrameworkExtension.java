package io.github.wadahiro.keycloak.testframework.quarkus;

import java.util.List;

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
import org.jboss.logging.Logger;

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

    public static final class SnapshotSupplier implements Supplier<QuarkusH2Snapshot, InjectQuarkusH2Snapshot> {
        @Override public int order() { return 2000; }

        @Override
        public List<org.keycloak.testframework.injection.Dependency> getDependencies(
                org.keycloak.testframework.injection.RequestedInstance<QuarkusH2Snapshot, InjectQuarkusH2Snapshot> context) {
            return org.keycloak.testframework.injection.DependenciesBuilder.create(KeycloakServer.class).build();
        }

        @Override
        public QuarkusH2Snapshot getValue(
                InstanceContext<QuarkusH2Snapshot, InjectQuarkusH2Snapshot> context) {
            rejectParallel(context);
            KeycloakServer server = context.getDependency(KeycloakServer.class);
            if (!(server instanceof QuarkusProcessServer quarkus)) {
                throw new IllegalArgumentException("@InjectQuarkusH2Snapshot requires kc.test.server=quarkus-process");
            }
            return new QuarkusH2Snapshot(quarkus);
        }

        @Override public boolean compatible(InstanceContext<QuarkusH2Snapshot, InjectQuarkusH2Snapshot> a,
                RequestedInstance<QuarkusH2Snapshot, InjectQuarkusH2Snapshot> b) { return true; }

        @Override
        public void onBeforeEach(InstanceContext<QuarkusH2Snapshot, InjectQuarkusH2Snapshot> context) {
            for (InstanceContext<?, ?> resource : context.getRegistry().getDeployedInstances()) {
                if (resource.getLifeCycle() == LifeCycle.METHOD) {
                    throw new IllegalArgumentException("H2 reset requires CLASS/GLOBAL resources; METHOD resource: "
                            + resource.getRequestedValueType().getSimpleName());
                }
                if (resource.getValue() instanceof io.github.wadahiro.keycloak.testframework.snapshot.H2SnapshotParticipant participant) {
                    context.getValue().addParticipant(participant);
                }
                if (resource != context) resource.registerDependent(context);
            }
            context.getValue().initialize();
        }

        @Override public void close(InstanceContext<QuarkusH2Snapshot, InjectQuarkusH2Snapshot> context) {
            context.getValue().close();
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
