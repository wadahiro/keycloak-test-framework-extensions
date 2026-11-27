package io.github.wadahiro.keycloak.testframework.fixture15;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.log.JBossLoggingEventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;

/**
 * Replaces Keycloak's built-in {@code jboss-logging} event listener (same SPI and id): it still logs through the
 * built-in listener and also records the event types it receives.
 */
public final class OverridingEventListenerProviderFactory extends JBossLoggingEventListenerProviderFactory {
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        EventListenerProvider builtIn = super.create(session);
        return new EventListenerProvider() {
            @Override
            public void onEvent(Event event) {
                EVENTS.add(event.getType().name());
                builtIn.onEvent(event);
            }

            @Override
            public void onEvent(AdminEvent event, boolean includeRepresentation) {
                builtIn.onEvent(event, includeRepresentation);
            }

            @Override
            public void close() {
                builtIn.close();
            }
        };
    }
}
