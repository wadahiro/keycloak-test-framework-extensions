package io.github.wadahiro.keycloak.runtime15;

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
            @Override public void scheduleTask(ScheduledTask task, long interval, String name) {
                schedule(new ScheduledTaskRunner(factory, task), interval, name);
            }
            @Override public TimerTaskContext cancelTask(String name) { return timer.cancelTask(name); }
            @Override public void close() { timer.close(); }
        };
    }
}
