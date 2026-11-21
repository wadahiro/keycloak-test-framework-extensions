package io.github.wadahiro.keycloak.testframework.runtimemodern;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.timer.*;
import org.keycloak.timer.basic.BasicTimerProviderFactory;
import org.keycloak.services.scheduled.ScheduledTaskRunner;

/** Keep the standard timer, but include every scheduled task in the reset barrier. */
public final class GatedTimerFactory implements TimerProviderFactory {
    private final BasicTimerProviderFactory delegate = new BasicTimerProviderFactory();
    private KeycloakSessionFactory factory;
    @Override public void init(Config.Scope config) { delegate.init(config); }
    @Override public void postInit(KeycloakSessionFactory factory) { this.factory = factory; delegate.postInit(factory); }
    @Override public String getId() { return "snapshot-barrier"; }
    @Override public void close() { delegate.close(); }
    @Override public TimerProvider create(KeycloakSession session) {
        TimerProvider timer = delegate.create(session);
        return new TimerProvider() {
            @Override public void schedule(Runnable task, long interval, String name) {
                timer.schedule(() -> RequestGate.read(task), interval, name);
            }
            public void schedule(Runnable task, long delay, long interval, String name) {
                try {
                    timer.getClass().getMethod("schedule",Runnable.class,long.class,long.class,String.class)
                            .invoke(timer,(Runnable)() -> RequestGate.read(task),delay,interval,name);
                } catch (NoSuchMethodException older) { schedule(task,interval,name); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }
            @SuppressWarnings("unchecked")
            public java.util.Map<String,TimerTaskContext> getTasks() {
                try { return (java.util.Map<String,TimerTaskContext>) timer.getClass().getMethod("getTasks").invoke(timer); }
                catch (NoSuchMethodException older) { return java.util.Map.of(); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }
            @Override public void scheduleTask(ScheduledTask task, long interval, String name) {
                schedule(new ScheduledTaskRunner(factory, task), interval, name);
            }
            @Override public TimerTaskContext cancelTask(String name) { return timer.cancelTask(name); }
            @Override public void close() { timer.close(); }
        };
    }
}
