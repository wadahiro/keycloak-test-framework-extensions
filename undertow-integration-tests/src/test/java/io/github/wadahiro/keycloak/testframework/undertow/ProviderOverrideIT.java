package io.github.wadahiro.keycloak.testframework.undertow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.RealmBuilder;
import org.keycloak.testframework.realm.RealmConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A provider dependency replaces a built-in provider with the same id and contributes themes, like a WildFly deployment. */
@KeycloakIntegrationTest(config = CustomSpiIT.ProviderServerConfig.class)
class ProviderOverrideIT {
    private static final String OVERRIDE = "io.github.wadahiro.keycloak.testframework.fixture15.OverridingEventListenerProviderFactory";
    private static final String BUILT_IN = "org.keycloak.events.log.JBossLoggingEventListenerProviderFactory";

    @InjectRealm(config = FixtureRealm.class)
    ManagedRealm realm;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void providerDependencyReplacesBuiltInProviderWithTheSameId() throws Exception {
        var login = HttpRequest.newBuilder(URI.create(realm.getBaseUrl() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username=unknown-user&password=wrong"))
                .build();
        assertEquals(401, http.send(login, HttpResponse.BodyHandlers.discarding()).statusCode());

        String state = get(realm.getBaseUrl() + "/custom-test-spi/event-listener");
        assertTrue(state.contains("factory=" + OVERRIDE + "\n"), state);
        assertTrue(state.contains("LOGIN_ERROR"), state);

        List<String> overrides = serverLog().filter(line -> line.startsWith("KCTF_PROVIDER OVERRIDE ")).toList();
        assertEquals(1, overrides.size(), overrides.toString());
        assertTrue(overrides.get(0).contains("spi=eventsListener id=jboss-logging provider=" + OVERRIDE + " replaces=" + BUILT_IN + " "),
                overrides.toString());
    }

    @Test
    void servesThemeAndThemeResourcesFromProviderDependency() throws Exception {
        String redirect = URLEncoder.encode(realm.getBaseUrl() + "/account/", StandardCharsets.UTF_8);
        String page = get(realm.getBaseUrl() + "/protocol/openid-connect/auth?client_id=account&response_type=code&scope=openid&redirect_uri=" + redirect);
        assertTrue(page.contains("kctf-fixture-theme-template"), page);
        assertTrue(page.contains("kctf-fixture-theme-resource-message"), page);
        assertTrue(page.contains("kctf-fixture-theme-resource-template"), page);

        var stylesheets = Pattern.compile("href=\"([^\"]+/login/kctf-fixture/css/(kctf-fixture-[a-z]+)\\.css)\"").matcher(page);
        int found = 0;
        while (stylesheets.find()) {
            String css = get(URI.create(realm.getBaseUrl()).resolve(stylesheets.group(1)).toString());
            assertTrue(css.contains("." + stylesheets.group(2) + " "), css);
            found++;
        }
        assertEquals(2, found, page);
    }

    private String get(String url) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), url + "\n" + response.body());
        return response.body();
    }

    /** The log of the child JVM serving this realm, found by the readiness line naming its URL. */
    private Stream<String> serverLog() throws IOException {
        String ready = "KCTF_CONTROL READY " + realm.getBaseUrl().replaceFirst("/realms/.*$", "");
        Path work = Path.of(System.getProperty("kc.test.server.extension.undertow.work"));
        try (Stream<Path> runs = Files.list(work)) {
            Path log = runs.map(run -> run.resolve("server.log")).filter(Files::isRegularFile)
                    .filter(file -> lines(file).anyMatch(ready::equals))
                    .max(Comparator.comparing(file -> file.toFile().lastModified())).orElseThrow();
            assertFalse(lines(log).noneMatch(line -> line.startsWith("KCTF_PROVIDER DEPLOY ")), log.toString());
            return lines(log);
        }
    }

    private static Stream<String> lines(Path file) {
        try {
            return Files.readAllLines(file).stream();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static class FixtureRealm implements RealmConfig {
        @Override
        public RealmBuilder configure(RealmBuilder builder) {
            RealmBuilder realm = builder.eventsListeners("jboss-logging");
            realm.build().setLoginTheme("kctf-fixture");
            return realm;
        }
    }
}
