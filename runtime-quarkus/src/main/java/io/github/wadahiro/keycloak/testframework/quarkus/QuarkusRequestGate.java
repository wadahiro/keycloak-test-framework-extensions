package io.github.wadahiro.keycloak.testframework.quarkus;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import io.quarkus.bootstrap.app.RunningQuarkusApplication;

/** Quiesces HTTP requests while the database and Keycloak caches are snapshotted or restored. */
final class QuarkusRequestGate {
    private Class<?> sharedGate;

    void bind(RunningQuarkusApplication application) throws Exception {
        sharedGate = application.getClassLoader().loadClass("io.github.wadahiro.keycloak.testframework.quarkus.RequestGate");
    }

    void install(RunningQuarkusApplication application) throws Exception {
        ClassLoader appLoader = application.getClassLoader();
        Class<?> routerClass = appLoader.loadClass("io.vertx.ext.web.Router");
        Class<?> routeClass = appLoader.loadClass("io.vertx.ext.web.Route");
        Class<?> contextClass = appLoader.loadClass("io.vertx.ext.web.RoutingContext");
        Class<?> handlerClass = appLoader.loadClass("io.vertx.core.Handler");
        if (sharedGate == null) bind(application);
        Object router = application.instance(routerClass);
        Object route = routerClass.getMethod("route").invoke(router);
        routeClass.getMethod("order", int.class).invoke(route, Integer.MIN_VALUE);
        InvocationHandler gateRequests = (proxy, method, args) -> {
            if (!method.getName().equals("handle") || args == null || args.length != 1) return null;
            Object context = args[0];
            invokeGate("enter");
            AtomicBoolean released = new AtomicBoolean();
            try {
                Object endHandler = Proxy.newProxyInstance(appLoader, new Class<?>[] {handlerClass},
                        (endProxy, endMethod, endArgs) -> {
                            if (endMethod.getName().equals("handle") && released.compareAndSet(false, true)) invokeGate("leave");
                            return null;
                        });
                contextClass.getMethod("addEndHandler", handlerClass).invoke(context, endHandler);
                contextClass.getMethod("next").invoke(context);
            } catch (Exception | Error failure) {
                if (released.compareAndSet(false, true)) invokeGate("leave");
                throw failure;
            }
            return null;
        };
        Object routeHandler = Proxy.newProxyInstance(appLoader, new Class<?>[] {handlerClass}, gateRequests);
        routeClass.getMethod("handler", handlerClass).invoke(route, routeHandler);
    }

    <T> T exclusive(CheckedSupplier<T> action) throws Exception {
        if (sharedGate == null) return action.get();
        invokeGate("beginExclusive");
        try {
            return action.get();
        } finally {
            invokeGate("endExclusive");
        }
    }

    private void invokeGate(String method) {
        try {
            sharedGate.getMethod(method).invoke(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to access the Keycloak snapshot gate", failure);
        }
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
