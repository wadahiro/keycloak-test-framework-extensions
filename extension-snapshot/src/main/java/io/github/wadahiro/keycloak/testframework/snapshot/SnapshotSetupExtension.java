package io.github.wadahiro.keycloak.testframework.snapshot;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.Order;
import org.keycloak.testframework.KeycloakIntegrationTestExtension;
import org.keycloak.testframework.injection.InstanceContext;
import org.keycloak.testframework.injection.Registry;

/** Captures the shared test baseline after Keycloak Test Framework's {@code @TestSetup} has run. */
@Order(Integer.MAX_VALUE)
public final class SnapshotSetupExtension implements BeforeEachCallback {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace
            .create(SnapshotSetupExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getRequiredTestClass();
        ExtensionContext.Store store = context.getParent().orElse(context).getStore(NAMESPACE);
        if (store.get(testClass) != null) {
            return;
        }

        Object testInstance = context.getRequiredTestInstance();
        Snapshot snapshot = snapshotField(testClass, testInstance);
        if (snapshot == null && testClass.isAnnotationPresent(ResetKeycloakState.class)) {
            Registry registry = KeycloakIntegrationTestExtension.getRegistry(context);
            snapshot = registry.getDeployedInstances().stream()
                    .map(InstanceContext::getValue)
                    .filter(Snapshot.class::isInstance)
                    .map(Snapshot.class::cast)
                    .findFirst()
                    .orElse(null);
        }
        if (snapshot == null) {
            if (isKeycloakIntegrationTest(testClass)) {
                throw new IllegalStateException("Snapshot was not activated; add @ResetKeycloakState or an @InjectSnapshot field and the matching runtime adapter extension");
            }
            return;
        }
        snapshot.capture();
        store.put(testClass, Boolean.TRUE);
    }

    private Snapshot snapshotField(Class<?> testClass, Object testInstance) throws IllegalAccessException {
        for (Class<?> current = testClass; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(InjectSnapshot.class)) {
                    if (!field.canAccess(testInstance)) {
                        field.setAccessible(true);
                    }
                    Object value = field.get(testInstance);
                    if (value instanceof Snapshot snapshot) {
                        return snapshot;
                    }
                }
            }
        }
        return null;
    }

    private boolean isKeycloakIntegrationTest(Class<?> testClass) {
        return hasKeycloakIntegrationAnnotation(testClass.getAnnotations(), new HashSet<>());
    }

    private boolean hasKeycloakIntegrationAnnotation(Annotation[] annotations, Set<Class<?>> visited) {
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.getName().equals("org.keycloak.testframework.annotations.KeycloakIntegrationTest")) {
                return true;
            }
            if (!annotationType.getName().startsWith("java.lang.annotation.") && visited.add(annotationType)
                    && hasKeycloakIntegrationAnnotation(annotationType.getAnnotations(), visited)) {
                return true;
            }
        }
        return false;
    }
}
