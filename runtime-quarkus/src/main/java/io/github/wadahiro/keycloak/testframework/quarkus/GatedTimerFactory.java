package io.github.wadahiro.keycloak.testframework.quarkus;

import java.lang.reflect.InvocationTargetException;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.scheduled.ScheduledTaskRunner;
import org.keycloak.timer.ScheduledTask;
import org.keycloak.timer.TimerProvider;
import org.keycloak.timer.TimerProviderFactory;
import org.keycloak.timer.basic.BasicTimerProviderFactory;
import org.keycloak.timer.TimerProvider.TimerTaskContext;

/** Wraps Keycloak's standard timer so background tasks wait while a snapshot is in progress. */
public final class GatedTimerFactory implements TimerProviderFactory {
    private final BasicTimerProviderFactory delegate = new BasicTimerProviderFactory();
    private KeycloakSessionFactory sessionFactory;

    @Override public void init(Config.Scope config) { delegate.init(config); }
    @Override public void postInit(KeycloakSessionFactory factory) { sessionFactory = factory; delegate.postInit(factory); }
    @Override public String getId() { return "snapshot-barrier"; }
    @Override public void close() { delegate.close(); }

    @Override
    public TimerProvider create(KeycloakSession session) {
        TimerProvider timer = delegate.create(session);
        return new TimerProvider() {
            @Override public void schedule(Runnable task, long interval, String name) {
                timer.schedule(() -> RequestGate.read(task), interval, name);
            }

            public void schedule(Runnable task, long delay, long interval, String name) {
                try {
                    timer.getClass().getMethod("schedule", Runnable.class, long.class, long.class, String.class)
                            .invoke(timer, (Runnable) () -> RequestGate.read(task), delay, interval, name);
                } catch (NoSuchMethodException olderKeycloak) {
                    schedule(task, interval, name);
                } catch (IllegalAccessException | InvocationTargetException failure) {
                    throw new IllegalStateException("Unable to schedule a gated Keycloak timer task", failure);
                }
            }

            @SuppressWarnings("unchecked")
            public java.util.Map<String, TimerTaskContext> getTasks() {
                try {
                    return (java.util.Map<String, TimerTaskContext>) timer.getClass().getMethod("getTasks").invoke(timer);
                } catch (NoSuchMethodException olderKeycloak) {
                    return java.util.Map.of();
                } catch (IllegalAccessException | InvocationTargetException failure) {
                    throw new IllegalStateException("Unable to inspect Keycloak timer tasks", failure);
                }
            }

            @Override public void scheduleTask(ScheduledTask task, long interval, String name) {
                schedule(new ScheduledTaskRunner(sessionFactory, task), interval, name);
            }
            @Override public TimerTaskContext cancelTask(String name) { return timer.cancelTask(name); }
            @Override public void close() { timer.close(); }
        };
    }
}
