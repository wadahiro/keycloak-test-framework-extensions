package io.github.wadahiro.keycloak.testframework.runtimemodern;

import java.lang.reflect.Method;
import org.keycloak.models.KeycloakSession;

/** Keycloak 25 moved its current-session context out of RESTEasy's context map. */
final class SessionContext {
    private static final Method SETTER = findSetter();
    private static Method findSetter() {
        try { return Class.forName("org.keycloak.utils.KeycloakSessionUtil").getMethod("setKeycloakSession",KeycloakSession.class); }
        catch (ClassNotFoundException absent) { return null; }
        catch (NoSuchMethodException failure) { throw new IllegalStateException(failure); }
    }
    static KeycloakSession set(KeycloakSession session) {
        if (SETTER == null) return null;
        try { return (KeycloakSession) SETTER.invoke(null,session); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
