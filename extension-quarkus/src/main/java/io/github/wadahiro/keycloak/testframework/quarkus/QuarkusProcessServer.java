package io.github.wadahiro.keycloak.testframework.quarkus;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.config.Config;
import org.keycloak.testframework.server.KeycloakServer;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/** Process-managed Quarkus Keycloak runtime bootstrapped from Maven-resolved artifacts. */
public final class QuarkusProcessServer implements KeycloakServer {
    private Process process;
    private BufferedWriter input;
    private Thread outputReader;
    private Thread shutdownHook;
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private String baseUrl;
    private Path runDirectory;

    @Override
    public synchronized void start(KeycloakServerConfigBuilder builder, boolean tlsEnabled) {
        if (process != null) throw new IllegalStateException("Quarkus process has already been started");
        try {
            Path javaHome = configuredJavaHome();
            RuntimeDescriptor runtime = RuntimeDescriptor.select(Config.get("kc.test.quarkus.version", null, String.class));
            String keycloakVersion = runtime.version();
            int serverJava = requiredJava(keycloakVersion);
            verifyServerJava(javaHome, serverJava, keycloakVersion);
            int httpPort = reservePort();
            int httpsPort = tlsEnabled ? reservePort() : httpPort;
            int port = tlsEnabled ? httpsPort : httpPort;
            if (builder == null) throw new IllegalArgumentException("A KeycloakServerConfigBuilder is required");
            setDefaultOption(builder, "db", "dev-mem");
            setDefaultOption(builder, "hostname-strict", "false");
            setDefaultOption(builder, "http-enabled", "true");
            int quarkusMajor = Integer.parseInt(keycloakVersion.split("[.]", 2)[0]);
            if (quarkusMajor >= 26) {
                setDefaultOption(builder, "spi-timer--provider", "snapshot-barrier");
            }
            builder.option("http-port", Integer.toString(httpPort));
            if (tlsEnabled) builder.option("https-port", Integer.toString(httpsPort));
            List<String> keycloakArgs = keycloakArgs(builder, keycloakVersion);
            ProviderSetup providers = providerSetup(builder);
            baseUrl = (tlsEnabled ? "https" : "http") + "://localhost:" + port;
            runDirectory = Path.of(Config.get("kc.test.quarkus.work", Path.of(System.getProperty("java.io.tmpdir"),
                    "keycloak-quarkus-runs").toString(), String.class));
            Files.createDirectories(runDirectory);
            runDirectory = Files.createTempDirectory(runDirectory, "quarkus-");
            String runtimeClasspath = runtime.classpath();
            verifyRuntimeClasspath(runtimeClasspath);
            var command = new java.util.ArrayList<String>(List.of(javaHome.resolve("bin").resolve(javaExecutable()).toString(),
                    "-Djava.util.logging.manager=org.jboss.logmanager.LogManager",
                    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", "-XX:+EnableDynamicAgentLoading",
                    "-cp", runtimeClasspath, "io.github.wadahiro.keycloak.testframework.quarkus.QuarkusServer"));
            command.addAll(keycloakArgs);
            ProcessBuilder builderProcess = new ProcessBuilder(command).redirectErrorStream(true);
            builderProcess.environment().put("KC_PORT", Integer.toString(port));
            builderProcess.environment().put("KCTF_HTTP_TEST_PORT", Integer.toString(httpPort));
            builderProcess.environment().put("KCTF_HTTPS_TEST_PORT", Integer.toString(httpsPort));
            builderProcess.environment().put("KCTF_PROVIDER_DEPENDENCIES", String.join(";", providers.coordinates()));
            builderProcess.environment().put("KCTF_LOCAL_PROVIDER_CLASSES", String.join(";", providers.localProviderClasses()));
            builderProcess.environment().put("KCTF_SCHEME", tlsEnabled ? "https" : "http");
            builderProcess.environment().put("KCTF_KEYCLOAK_VERSION", keycloakVersion);
            builderProcess.environment().put("KCTF_HOME_DIR", runDirectory.resolve("home").toString());
            if (serverJava < 25) {
                builderProcess.environment().put("KEYCLOAK_ADMIN", Config.getAdminUsername());
                builderProcess.environment().put("KEYCLOAK_ADMIN_PASSWORD", Config.getAdminPassword());
            }
            process = builderProcess.start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            CompletableFuture<Void> ready = new CompletableFuture<>();
            outputReader = Thread.ofPlatform().daemon().name("quarkus-bootstrap-output").start(() -> readOutput(ready, port));
            shutdownHook = new Thread(this::stop, "quarkus-bootstrap-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            ready.get(180, TimeUnit.SECONDS);
            assertReady();
            if (Integer.parseInt(keycloakVersion.split("[.]", 2)[0]) < 26) bootstrapFrameworkAdminClient(tlsEnabled);
        } catch (Exception e) {
            stop();
            throw new IllegalStateException("Unable to start the Quarkus Keycloak process; logs: " + runDirectory, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> keycloakArgs(KeycloakServerConfigBuilder builder, String keycloakVersion) throws Exception {
        var method = KeycloakServerConfigBuilder.class.getDeclaredMethod("toArgs");
        method.setAccessible(true);
        List<String> args = (List<String>) method.invoke(builder);
        int major = Integer.parseInt(keycloakVersion.split("[.]", 2)[0]);
        if (major >= 26) return args;
        return args.stream().filter(arg -> !arg.startsWith("--bootstrap-admin-client-id=")
                && !arg.startsWith("--bootstrap-admin-client-secret=")
                && !arg.startsWith("--bootstrap-admin-username=")
                && !arg.startsWith("--bootstrap-admin-password=")
                && !arg.startsWith("--shutdown-delay=") && !arg.startsWith("--shutdown-timeout=")).toList();
    }

    @SuppressWarnings("unchecked")
    private static void setDefaultOption(KeycloakServerConfigBuilder builder, String key, String value) throws Exception {
        Field optionsField = KeycloakServerConfigBuilder.class.getDeclaredField("options");
        optionsField.setAccessible(true);
        Map<String, String> options = (Map<String, String>) optionsField.get(builder);
        if (!options.containsKey(key)) builder.option(key, value);
    }

    @SuppressWarnings("unchecked")
    private static ProviderSetup providerSetup(KeycloakServerConfigBuilder builder) throws Exception {
        var dependenciesMethod = KeycloakServerConfigBuilder.class.getDeclaredMethod("toDependencies");
        dependenciesMethod.setAccessible(true);
        var dependencies = (Iterable<Object>) dependenciesMethod.invoke(builder);
        var updater = Class.forName("org.keycloak.testframework.util.MavenProjectUtil")
                .getMethod("updateDependencyDetails", Class.forName("org.keycloak.testframework.server.KeycloakDependency"));
        Class<?> projectUtil = Class.forName("org.keycloak.testframework.util.MavenProjectUtil");
        List<String> coordinates = new java.util.ArrayList<>();
        List<String> localProviderClasses = new java.util.ArrayList<>();
        for (Object dependency : dependencies) {
            boolean currentProject = (boolean) dependency.getClass().getMethod("dependencyCurrentProject").invoke(dependency);
            Object updated = updater.invoke(null, dependency);
            String groupId = (String) updated.getClass().getMethod("getGroupId").invoke(updated);
            String artifactId = (String) updated.getClass().getMethod("getArtifactId").invoke(updated);
            String version = (String) updated.getClass().getMethod("getVersion").invoke(updated);
            if (groupId == null || artifactId == null || version == null || version.isBlank()) {
                throw new IllegalArgumentException("Unable to resolve Keycloak provider dependency: " + dependency);
            }
            if (currentProject) {
                Object module = projectUtil.getMethod("getCurrentModule").invoke(null);
                Object classesDir = module.getClass().getMethod("getClassesDir").invoke(module);
                localProviderClasses.add(classesDir.toString());
            } else {
                try {
                    Object module = projectUtil.getMethod("findLocalModule", String.class, String.class).invoke(null, groupId, artifactId);
                    Object classesDir = module.getClass().getMethod("getClassesDir").invoke(module);
                    localProviderClasses.add(classesDir.toString());
                } catch (java.lang.reflect.InvocationTargetException notLocal) {
                    coordinates.add(groupId + ":" + artifactId + ":" + version);
                }
            }
        }
        return new ProviderSetup(coordinates, localProviderClasses);
    }

    private record ProviderSetup(List<String> coordinates, List<String> localProviderClasses) { }

    private void readOutput(CompletableFuture<Void> ready, int port) {
        Path log = runDirectory.resolve("server.log");
        try (var reader = process.inputReader(StandardCharsets.UTF_8);
                var writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                writer.write(line);
                writer.newLine();
                writer.flush();
                if (line.startsWith("KCTF_SERVER_STARTED port=" + port)) ready.complete(null);
                if (!ready.isDone() && (line.startsWith("ERROR: Failed to start server") || line.startsWith("Exception in thread"))) {
                    ready.completeExceptionally(new IOException("Quarkus failed before readiness; logs: " + log));
                }
                if (line.startsWith("KCTF_CONTROL ")) replies.add(line);
            }
            if (!ready.isDone()) ready.completeExceptionally(new IOException("Quarkus exited before readiness; logs: " + log));
        } catch (IOException e) {
            ready.completeExceptionally(e);
        }
    }

    private void assertReady() throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (baseUrl.startsWith("https:")) builder.sslContext(trustAllContext());
        try (HttpClient client = builder.build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/realms/master"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) throw new IOException("Keycloak readiness returned " + response.statusCode());
        }
    }

    private static SSLContext trustAllContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
        }}, new java.security.SecureRandom());
        return context;
    }

    private void bootstrapFrameworkAdminClient(boolean tlsEnabled) throws Exception {
        var builder = KeycloakBuilder.builder().serverUrl(baseUrl).realm("master").clientId("admin-cli")
                .username(Config.getAdminUsername()).password(Config.getAdminPassword()).grantType(OAuth2Constants.PASSWORD);
        if (tlsEnabled) builder.resteasyClient(Keycloak.getClientProvider().newRestEasyClient(null, trustAllContext(), false));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            try (Keycloak admin = builder.build()) {
                var master = admin.realm("master");
                ClientRepresentation client = new ClientRepresentation();
                client.setClientId(Config.getAdminClientId());
                client.setEnabled(true);
                client.setProtocol("openid-connect");
                client.setPublicClient(false);
                client.setServiceAccountsEnabled(true);
                client.setSecret(Config.getAdminClientSecret());
                try (Response response = master.clients().create(client)) {
                    if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
                        throw new IOException("Unable to bootstrap Test Framework admin client: HTTP " + response.getStatus());
                    }
                }
                String clientId = master.clients().findByClientId(Config.getAdminClientId()).get(0).getId();
                UserRepresentation serviceAccount = master.clients().get(clientId).getServiceAccountUser();
                RoleRepresentation adminRole = master.roles().get("admin").toRepresentation();
                master.users().get(serviceAccount.getId()).roles().realmLevel().add(List.of(adminRole));
                return;
            } catch (ProcessingException e) {
                if (!hasCause(e, NotAuthorizedException.class) || System.nanoTime() >= deadline) throw e;
                Thread.sleep(100);
            }
        }
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }

    private Path configuredJavaHome() {
        String configured = Config.get("kc.test.quarkus.java.home", System.getenv("KEYCLOAK_JAVA_HOME"), String.class);
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("Set KEYCLOAK_JAVA_HOME or kc.test.quarkus.java.home to JDK 25");
        }
        return Path.of(configured);
    }

    private void verifyServerJava(Path javaHome, int requiredJava, String keycloakVersion) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(javaHome.resolve("release"))) {
            properties.load(reader);
        }
        if (!properties.getProperty("JAVA_VERSION", "").replace("\"", "").startsWith(requiredJava + ".")) {
            throw new IllegalArgumentException("Keycloak " + keycloakVersion + " Quarkus runtime requires JDK " + requiredJava + ": " + javaHome);
        }
    }

    private int requiredJava(String keycloakVersion) {
        int major = Integer.parseInt(keycloakVersion.split("[.]", 2)[0]);
        if (major >= 26) return 25;
        if (major >= 25) return 21;
        if (major >= 20) return 17;
        throw new IllegalArgumentException("Quarkus bootstrap does not support Keycloak " + keycloakVersion
                + "; Keycloak 15 is not a Quarkus distribution");
    }

    private static void verifyRuntimeClasspath(String classpath) {
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("The prepared Quarkus runtime classpath is empty. Run keycloak-test-runtime:prepare.");
        }
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!Files.isRegularFile(Path.of(entry))) {
                throw new IllegalStateException("Prepared Quarkus runtime dependency is missing: " + entry
                        + ". Rerun keycloak-test-runtime:prepare.");
            }
        }
    }

    synchronized void command(String operation, String lease) {
        if (!isRunning()) throw new IllegalStateException("Quarkus server is not running; logs: " + runDirectory);
        try {
            input.write(operation + " " + lease);
            input.newLine();
            input.flush();
            String response = replies.poll(30, TimeUnit.SECONDS);
            if (response == null) throw new IOException("Timed out waiting for " + operation + " response");
            if (!response.equals("KCTF_CONTROL OK " + operation + " " + lease)) {
                throw new IOException("Quarkus runtime rejected " + operation + ": " + response);
            }
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("Quarkus snapshot command failed; logs: " + runDirectory, failure);
        }
    }

    private String javaExecutable() { return System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"; }

    private int reservePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Override
    public String getBaseUrl() { return baseUrl; }

    @Override
    public String getManagementBaseUrl() { return baseUrl; }

    public long getPid() {
        if (process == null) throw new IllegalStateException("Quarkus server has not started");
        return process.pid();
    }

    public synchronized boolean isRunning() { return process != null && process.isAlive(); }

    @Override
    public synchronized void stop() {
        try {
            if (input != null) {
                input.write("STOP");
                input.newLine();
                input.flush();
                input.close();
                input = null;
            }
            if (process != null && process.isAlive() && !process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            if (outputReader != null && outputReader != Thread.currentThread()) outputReader.join(5000);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
        } finally {
            if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { }
                shutdownHook = null;
            }
        }
    }
}
