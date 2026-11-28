package io.github.wadahiro.keycloak.testframework.undertow;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import io.github.wadahiro.keycloak.testframework.providers.ProviderFiles;
import io.github.wadahiro.keycloak.testframework.providers.ProviderFiles.ProviderFile;
import org.keycloak.testframework.config.Config;
import org.keycloak.testframework.server.*;

/** Maven-resolved server runtime runs in a child JVM, isolated from the test classpath. */
public final class UndertowProcessServer implements KeycloakServer {
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private Process process;
    private BufferedWriter input;
    private Thread reader;
    private Thread hook;
    private Path run;
    private String url;
    private boolean failed;

    public static String value(String name, String fallback) { return Config.get("kc.test.server.extension.undertow." + name, fallback, String.class); }

    @Override public synchronized void start(KeycloakServerConfigBuilder builder, boolean tlsEnabled) {
        if (tlsEnabled) throw new IllegalArgumentException("Undertow runtime supports its fixed HTTP/H2 configuration only");
        if (process != null || failed) throw new IllegalStateException("This server instance has already been started");
        try {
            RuntimeDescriptor runtime = RuntimeDescriptor.select(value("version", null));
            String homeValue = value("java.home", System.getenv("KEYCLOAK_JAVA_HOME"));
            if (homeValue == null) throw new IllegalArgumentException("Set KEYCLOAK_JAVA_HOME or kc.test.server.extension.undertow.java.home to the selected server JDK");
            Path javaHome = Path.of(homeValue);
            Properties release = new Properties();
            try (Reader stream = Files.newBufferedReader(javaHome.resolve("release"))) { release.load(stream); }
            if (!release.getProperty("JAVA_VERSION", "").replace("\"", "").startsWith(runtime.javaFeature() + ".")) {
                throw new IllegalArgumentException("Keycloak " + runtime.version() + " requires JDK " + runtime.javaFeature() + ": " + javaHome);
            }
            Path work = Path.of(value("work", Path.of(System.getProperty("java.io.tmpdir"), "keycloak-undertow-runs").toString()));
            Files.createDirectories(work);
            run = Files.createTempDirectory(work, "server-");
            for (String entry : runtime.classpath().split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!Files.isRegularFile(Path.of(entry))) throw new IllegalStateException("Prepared Keycloak runtime dependency is missing: " + entry + ". Re-run keycloak-test-runtime:prepare.");
            }
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            List<String> commandLine = new ArrayList<>();
            commandLine.add(javaHome.resolve("bin").resolve(executable).toString());
            if (!runtime.version().startsWith("15.")) {
                commandLine.add("-Djava.util.logging.manager=org.jboss.logmanager.LogManager");
            }
            commandLine.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            commandLine.add("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
            List<String> providers = providerDeployments(builder, run);
            if (!providers.isEmpty()) {
                // Kept off -cp: the child deploys them like a WildFly deployment so they can replace built-in providers.
                Path providerList = run.resolve("provider-dependencies.txt");
                Files.write(providerList, providers, StandardCharsets.UTF_8);
                commandLine.add("-Dkctf.provider.dependencies=" + providerList);
            }
            Map<String, String> spiOptions = spiOptions(builder);
            if (!spiOptions.isEmpty()) {
                Path spiConfig = run.resolve("spi-options.properties");
                Properties properties = new Properties();
                properties.putAll(spiOptions);
                try (Writer out = Files.newBufferedWriter(spiConfig, StandardCharsets.UTF_8)) { properties.store(out, null); }
                commandLine.add("-Dkctf.spi.options=" + spiConfig);
            }
            if (proxyHeaders(builder)) commandLine.add("-Dkctf.proxy.headers=xforwarded");
            commandLine.addAll(List.of("-cp", runtime.classpath(), runtime.mainClass(), run.toString()));
            ProcessBuilder command = new ProcessBuilder(commandLine);
            command.environment().put("KCTF_RUNTIME_JAVA", Integer.toString(runtime.javaFeature()));
            command.environment().put("KCTF_ADMIN_USERNAME", value("admin.username", "admin"));
            command.environment().put("KCTF_ADMIN_PASSWORD", value("admin.password", "admin"));
            process = command.redirectErrorStream(true).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = Thread.ofPlatform().daemon().name("keycloak-undertow-control").start(this::readOutput);
            hook = new Thread(this::stop, "keycloak-undertow-stop");
            Runtime.getRuntime().addShutdownHook(hook);
            String ready = receive(120);
            if (!ready.startsWith("KCTF_CONTROL READY ")) throw new IOException("Unexpected runtime startup response: " + ready);
            url = ready.substring("KCTF_CONTROL READY ".length());
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var response = client.send(HttpRequest.newBuilder(URI.create(url + "/realms/master"))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 200) throw new IOException("Runtime readiness failed: " + response.statusCode());
            }
            Files.writeString(work.resolve("lifecycle.log"), "START " + process.pid() + " " + url + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) {
            failed = true;
            stop();
            throw new IllegalStateException("Cannot start the selected Keycloak runtime; logs: " + run, failure);
        }
    }

    /**
     * One line per deployment for the child, in deployment order: the deployment name and its root paths, separated by
     * tabs. {@code dependency(...)} artifacts come first, then {@link ProviderFiles} in the order they were added; an EAR
     * is expanded under the run directory and becomes one deployment of its module and library JARs.
     */
    static List<String> providerDeployments(KeycloakServerConfigBuilder builder, Path run) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String path : providerPaths(builder)) {
            if (!Files.exists(Path.of(path))) throw new IllegalStateException("Provider dependency is missing: " + path);
            lines.add(Path.of(path).getFileName() + "\t" + path);
        }
        int index = 0;
        for (ProviderFile file : ProviderFiles.of(builder)) {
            String name = file.path().getFileName().toString();
            if (file.kind() == ProviderFiles.Kind.EAR) {
                List<Path> members = EarArchive.members(file.path(), run.resolve("provider-files").resolve(index++ + "-" + name));
                StringBuilder line = new StringBuilder(name);
                for (Path member : members) line.append('\t').append(member);
                lines.add(line.toString());
            } else {
                lines.add(name + "\t" + file.path());
            }
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static List<String> providerPaths(KeycloakServerConfigBuilder builder) throws Exception {
        if (builder == null) return List.of();
        for (String fieldName : List.of("features", "featuresDisabled")) {
            var field = KeycloakServerConfigBuilder.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            if (!((java.util.Collection<?>) field.get(builder)).isEmpty()) {
                throw new IllegalArgumentException("Undertow 15 supports KeycloakServerConfigBuilder provider dependencies, provider files, SPI options and proxy-headers only; unsupported setting: " + fieldName);
            }
        }
        spiOptions(builder);
        var method = KeycloakServerConfigBuilder.class.getDeclaredMethod("toDependencies");
        method.setAccessible(true);
        var dependencies = (Iterable<Object>) method.invoke(builder);
        var updater = Class.forName("org.keycloak.testframework.util.MavenProjectUtil")
                .getMethod("updateDependencyDetails", Class.forName("org.keycloak.testframework.server.KeycloakDependency"));
        var resolver = Class.forName("org.keycloak.it.utils.Maven").getMethod("resolveArtifact", String.class, String.class);
        List<String> paths = new ArrayList<>();
        for (Object dependency : dependencies) {
            Object updated = updater.invoke(null, dependency);
            String groupId = (String) updated.getClass().getMethod("getGroupId").invoke(updated);
            String artifactId = (String) updated.getClass().getMethod("getArtifactId").invoke(updated);
            if ((boolean) updated.getClass().getMethod("dependencyCurrentProject").invoke(updated)) {
                Object module = Class.forName("org.keycloak.testframework.util.MavenProjectUtil").getMethod("getCurrentModule").invoke(null);
                paths.add(module.getClass().getMethod("getClassesDir").invoke(module).toString());
            } else {
                paths.add(resolver.invoke(null, groupId, artifactId).toString());
            }
        }
        return paths;
    }

    /**
     * The builder's {@code spiOption(spi, provider, key, value)} settings ({@code spi-<spi>--<provider>--<key>}), which
     * Keycloak 15 reads from its JSON configuration as {@code <spi>.<provider>.<key>}; {@code proxy-headers} is handled by {@link #proxyHeaders} and any other option
     * is rejected.
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> spiOptions(KeycloakServerConfigBuilder builder) throws Exception {
        if (builder == null) return Map.of();
        var field = KeycloakServerConfigBuilder.class.getDeclaredField("options");
        field.setAccessible(true);
        Map<String, String> spi = new LinkedHashMap<>();
        for (var option : ((Map<String, String>) field.get(builder)).entrySet()) {
            if (option.getKey().equals(PROXY_HEADERS) || ProviderFiles.isOption(option.getKey())) continue;
            var name = SPI_OPTION.matcher(option.getKey());
            if (!name.matches()) {
                throw new IllegalArgumentException("Undertow 15 supports SPI options (spiOption) and proxy-headers only; unsupported option: " + option.getKey());
            }
            spi.put(name.group(1) + "." + name.group(2) + "." + name.group(3), option.getValue());
        }
        return spi;
    }

    /**
     * Whether the builder sets {@code proxy-headers=xforwarded}, which makes the embedded Undertow server take the client
     * address, host, port and scheme from {@code X-Forwarded-*} headers, like WildFly's {@code proxy-address-forwarding}.
     */
    @SuppressWarnings("unchecked")
    static boolean proxyHeaders(KeycloakServerConfigBuilder builder) throws Exception {
        if (builder == null) return false;
        var field = KeycloakServerConfigBuilder.class.getDeclaredField("options");
        field.setAccessible(true);
        String value = ((Map<String, String>) field.get(builder)).get(PROXY_HEADERS);
        if (value == null) return false;
        if (!value.equals("xforwarded")) {
            throw new IllegalArgumentException("Undertow 15 supports proxy-headers=xforwarded only; unsupported value: " + value);
        }
        return true;
    }

    private static final String PROXY_HEADERS = "proxy-headers";
    private static final java.util.regex.Pattern SPI_OPTION = java.util.regex.Pattern.compile("spi-([^.]+?)--([^.]+?)--([^.]+)");

    private void readOutput() {
        try (BufferedReader lines = process.inputReader(StandardCharsets.UTF_8);
                BufferedWriter log = Files.newBufferedWriter(run.resolve("server.log"))) {
            for (String line; (line = lines.readLine()) != null;) {
                log.write(line); log.newLine(); log.flush();
                if (line.startsWith("KCTF_CONTROL ")) replies.add(line);
            }
        } catch (IOException failure) {
            replies.add("KCTF_CONTROL IO_ERROR " + failure.getClass().getSimpleName());
        } finally { replies.add("KCTF_CONTROL EXIT"); }
    }

    private String receive(int seconds) throws Exception {
        String reply = replies.poll(seconds, TimeUnit.SECONDS);
        if (reply == null) throw new IOException("Runtime control timed out after " + seconds + "s");
        return reply;
    }

    synchronized void command(String op, String lease) {
        if (failed || !isRunning()) throw new IllegalStateException("Undertow server is unavailable; start a fresh test JVM. Logs: " + run);
        try {
            input.write(op + " " + lease); input.newLine(); input.flush();
            String reply = receive(30);
            if (!reply.startsWith("KCTF_CONTROL OK " + op + " " + lease + " ")) {
                throw new IOException("Runtime rejected " + op + ": " + reply);
            }
        } catch (Exception failure) {
            failed = true;
            stop();
            throw new IllegalStateException("H2 " + op + " failed; server stopped to prevent reuse of partial state. Logs: " + run, failure);
        }
    }

    public synchronized long pid() { if (process == null) throw new IllegalStateException("Server has not started"); return process.pid(); }
    public synchronized boolean isRunning() { return !failed && process != null && process.isAlive(); }
    public Path getRunDirectory() { return run; }
    @Override public String getBaseUrl() { return url; }
    @Override public String getManagementBaseUrl() { return url; }

    @Override public synchronized void stop() {
        try {
            if (input != null) {
                try { input.write("STOP\n"); input.flush(); input.close(); } catch (IOException ignored) { }
                input = null;
            }
            if (process != null && process.isAlive() && !process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            if (reader != null && reader != Thread.currentThread()) reader.join(5000);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
        } finally {
            if (hook != null && Thread.currentThread() != hook) {
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
                hook = null;
            }
        }
    }
}
