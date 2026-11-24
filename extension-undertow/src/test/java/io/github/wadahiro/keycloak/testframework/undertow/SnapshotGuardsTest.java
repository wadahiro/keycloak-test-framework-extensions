package io.github.wadahiro.keycloak.testframework.undertow;

import java.lang.reflect.Proxy;
import java.util.*;
import io.github.wadahiro.keycloak.testframework.snapshot.InjectSnapshot;
import io.github.wadahiro.keycloak.testframework.snapshot.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.keycloak.testframework.injection.*;
import org.keycloak.testframework.server.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotGuardsTest {
    @InjectSnapshot UndertowSnapshot field;
    private InjectSnapshot annotation() throws Exception { return getClass().getDeclaredField("field").getAnnotation(InjectSnapshot.class); }
    static final class TestRegistry extends Registry {
        boolean parallel;
        @Override public ExtensionContext getCurrentContext() {
            return (ExtensionContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ExtensionContext.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getConfigurationParameter")) return Optional.of(Boolean.toString(parallel));
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
    private InstanceContext<Snapshot, InjectSnapshot> context(TestRegistry registry, KeycloakServer server) throws Exception {
        return new InstanceContext<>(-1, registry, new UndertowExtension.SnapshotSupplier(), annotation(), Snapshot.class, List.of()) {
            @Override public <D> D getDependency(Class<D> type) { return type.cast(server); }
        };
    }
    @Test void rejectsParallelExecutionBeforeSnapshotCreation() throws Exception {
        TestRegistry registry = new TestRegistry(); registry.parallel = true;
        var context = context(registry, new UndertowProcessServer());
        var failure = assertThrows(IllegalArgumentException.class, () -> new UndertowExtension.SnapshotSupplier().getValue(context));
        assertTrue(failure.getMessage().contains("parallel"));
    }
    @Test void rejectsOtherBackends() throws Exception {
        KeycloakServer other = new KeycloakServer() {
            public void start(KeycloakServerConfigBuilder builder, boolean tls) { }
            public void stop() { }
            public String getBaseUrl() { return "unused"; }
            public String getManagementBaseUrl() { return "unused"; }
        };
        var context = context(new TestRegistry(), other);
        assertThrows(IllegalArgumentException.class, () -> new UndertowExtension.SnapshotSupplier().getValue(context));
    }
    @Test void rejectsMethodScopedResourcesBeforeCapture() throws Exception {
        TestRegistry registry = new TestRegistry();
        Supplier<Object, InjectSnapshot> methodSupplier = new Supplier<>() {
            public Object getValue(InstanceContext<Object, InjectSnapshot> c) { return new Object(); }
            public boolean compatible(InstanceContext<Object, InjectSnapshot> a, RequestedInstance<Object, InjectSnapshot> b) { return true; }
            public LifeCycle getDefaultLifecycle() { return LifeCycle.METHOD; }
        };
        registry.getDeployedInstances().add(new InstanceContext<>(-1, registry, methodSupplier, annotation(), Object.class, List.of()));
        var context = context(registry, new UndertowProcessServer());
        var failure = assertThrows(IllegalArgumentException.class, () -> new UndertowExtension.SnapshotSupplier().onBeforeEach(context));
        assertTrue(failure.getMessage().contains("METHOD"));
    }
}
