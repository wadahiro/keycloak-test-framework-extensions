package io.github.wadahiro.keycloak.testframework.quarkus;

import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.net.URLClassLoader;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.eclipse.aether.artifact.DefaultArtifact;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.AdditionalDependency;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.app.RunningQuarkusApplication;
import io.quarkus.bootstrap.app.StartupAction;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.DependencyBuilder;
import io.quarkus.runtime.configuration.QuarkusConfigFactory;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

public class QuarkusServer {
    public static void main(String[] args) throws Exception {
        System.out.println("KCTF_CHILD_JAVA=" + Runtime.version().feature());
        System.setProperty("quarkus.http.test-port", System.getenv().getOrDefault("KCTF_HTTP_TEST_PORT", "18080"));
        System.setProperty("quarkus.http.test-ssl-port", System.getenv().getOrDefault("KCTF_HTTPS_TEST_PORT", "18443"));
        System.setProperty("quarkus.arc.unremovable-types", "io.vertx.ext.web.Router");
        System.setProperty("java.util.concurrent.ForkJoinPool.common.threadFactory", "io.quarkus.bootstrap.forkjoin.QuarkusForkJoinWorkerThreadFactory");
        String port = System.getenv().getOrDefault("KC_PORT", "18080");
        int versionMajor = Integer.parseInt(System.getenv().getOrDefault("KCTF_KEYCLOAK_VERSION", "26.8.2").split("[.]", 2)[0]);
        Path home = Path.of(System.getenv().getOrDefault("KCTF_HOME_DIR",
                Path.of(System.getProperty("java.io.tmpdir"), "kc-quarkus-test-home").toString()));
        Files.createDirectories(home);
        installProviderDependencies(home);
        Path timerProvider = versionMajor >= 23 ? installRequestGateArchive(home, versionMajor >= 26) : null;
        String[] kcArgs = args.length == 0 ? new String[] {"start-dev", "--db=dev-mem", "--http-port=" + port,
                "--hostname-strict=false", "--http-enabled=true", "--bootstrap-admin-client-id=temp-admin",
                "--bootstrap-admin-client-secret=mysecret", "--bootstrap-admin-username=admin", "--bootstrap-admin-password=admin"} : args;
        ApplicationModel model = resolveApplicationModel();
        QuarkusBootstrap.Builder builder = QuarkusBootstrap.builder()
                .setExistingModel(model)
                .setApplicationRoot(model.getApplicationModule().getModuleDir().toPath())
                .setTargetDirectory(model.getApplicationModule().getModuleDir().toPath())
                .setIsolateDeployment(true)
                .setFlatClassPath(true)
                .setMode(QuarkusBootstrap.Mode.TEST);
        if (timerProvider != null) builder.addAdditionalApplicationArchive(new AdditionalDependency(timerProvider, false, true));
        setKeycloakHome(home);
        CuratedApplication curated = builder.build().bootstrap();
        AugmentAction augmentor = curated.createAugmentor();
        if (versionMajor >= 26 && !initSys26(kcArgs)) {
            throw new IllegalStateException("Keycloak CLI did not prepare the start-dev runtime");
        }
        StartupAction startupAction = augmentor.createInitialRuntimeApplication();
        RunningQuarkusApplication application = startupAction.runMainClass(kcArgs);
        QuarkusRequestGate requestGate = new QuarkusRequestGate();
        if (versionMajor >= 23) {
            requestGate.bind(application);
            requestGate.install(application);
        }
        else System.out.println("KCTF_REQUEST_GATE_DISABLED keycloak=" + versionMajor);
        QuarkusSnapshotControl snapshot = new QuarkusSnapshotControl(application, requestGate, versionMajor >= 26);
        System.out.println("KCTF_SERVER_STARTED port=" + port);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = input.readLine()) != null;) {
                if ("STOP".equals(line)) break;
                String[] fields = line.split(" ", -1);
                if (fields.length != 2) throw new IllegalArgumentException("Invalid control command: " + line);
                try {
                    switch (fields[0]) {
                        case "CAPTURE" -> snapshot.capture();
                        case "RESTORE" -> snapshot.restore();
                        case "RELEASE" -> snapshot.release();
                        default -> throw new IllegalArgumentException("Unknown control command: " + fields[0]);
                    }
                    System.out.println("KCTF_CONTROL OK " + fields[0] + " " + fields[1]);
                } catch (Throwable failure) {
                    failure.printStackTrace(System.err);
                    System.out.println("KCTF_CONTROL ERROR " + fields[0] + " " + fields[1]);
                    System.out.flush();
                    break;
                }
                System.out.flush();
            }
        } finally {
            snapshot.close();
            application.close();
            QuarkusConfigFactory.setConfig(null);
            ConfigProviderResolver resolver = ConfigProviderResolver.instance();
            resolver.releaseConfig(resolver.getConfig());
            curated.close();
        }
    }

    private static Path installRequestGateArchive(Path home, boolean includeTimerProvider) throws Exception {
        Path providers = home.resolve("providers");
        Files.createDirectories(providers);
        Path jarFile = providers.resolve("keycloak-test-request-gate.jar");
        var location = Path.of(QuarkusServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarFile))) {
            if (Files.isDirectory(location)) {
                Path packageDirectory = location.resolve("io/github/wadahiro/keycloak/testframework/quarkus");
                try (var files = Files.list(packageDirectory)) {
                    String includedClasses = includeTimerProvider ? "(GatedTimerFactory|RequestGate)" : "RequestGate";
                    for (Path file : files.filter(p -> p.getFileName().toString().matches(includedClasses + "(\\$.*)?\\.class")).toList()) {
                        addJarFile(output, location, file);
                    }
                }
            } else {
                try (JarFile source = new JarFile(location.toFile())) {
                    String includedClasses = includeTimerProvider ? "(GatedTimerFactory|RequestGate)" : "RequestGate";
                    for (JarEntry entry : source.stream().filter(e -> e.getName().matches("io/github/wadahiro/keycloak/testframework/quarkus/" + includedClasses + "(\\$.*)?\\.class")).toList()) {
                        output.putNextEntry(new JarEntry(entry.getName()));
                        try (var input = source.getInputStream(entry)) { input.transferTo(output); }
                        output.closeEntry();
                    }
                }
            }
            if (includeTimerProvider) {
                output.putNextEntry(new JarEntry("META-INF/services/org.keycloak.timer.TimerProviderFactory"));
                output.write("io.github.wadahiro.keycloak.testframework.quarkus.GatedTimerFactory\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jarFile;
    }

    private static void addJarFile(JarOutputStream output, Path root, Path file) throws Exception {
        String name = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
        output.putNextEntry(new JarEntry(name));
        Files.copy(file, output);
        output.closeEntry();
    }

    private static ApplicationModel resolveApplicationModel() throws Exception {
        String version = System.getenv().getOrDefault("KCTF_KEYCLOAK_VERSION", "26.8.2");
        Path moduleDir = Files.createTempDirectory("kc-quarkus-test-runtime-");
        Dependency server = DependencyBuilder.newInstance()
                .setGroupId("org.keycloak")
                .setArtifactId("keycloak-quarkus-server")
                .setVersion(version)
                .addExclusion("org.jboss.logmanager", "log4j-jboss-logmanager")
                .build();
        WorkspaceModule.Mutable module = WorkspaceModule.builder()
                .setModuleId(WorkspaceModuleId.of("io.github.wadahiro.keycloak.testframework", "keycloak-quarkus-test-runtime", version))
                .setModuleDir(moduleDir)
                .setBuildDir(moduleDir)
                .addDependencyConstraint(Dependency.pomImport("org.keycloak", "keycloak-quarkus-parent", version))
                .addDependency(server)
                .addDependency(DependencyBuilder.newInstance().setGroupId("com.oracle.database.jdbc").setArtifactId("ojdbc17").setVersion("23.26.2.0.0").build())
                .addDependency(DependencyBuilder.newInstance().setGroupId("com.oracle.database.nls").setArtifactId("orai18n").setVersion("23.26.2.0.0").build());
        WorkspaceModule builtModule = module.build();
        MavenArtifactResolver resolver = MavenArtifactResolver.builder()
                .setWorkspaceDiscovery(true)
                .setOffline(false)
                .build();
        return new BootstrapAppModelResolver(resolver).resolveModel(builtModule);
    }

    private static void installProviderDependencies(Path home) throws Exception {
        Path providers = home.resolve("providers");
        Files.createDirectories(providers);
        String coordinates = System.getenv().getOrDefault("KCTF_PROVIDER_DEPENDENCIES", "");
        if (!coordinates.isBlank()) {
            MavenArtifactResolver resolver = MavenArtifactResolver.builder().setWorkspaceDiscovery(true).setOffline(false).build();
            for (String coordinate : coordinates.split(";")) {
                if (coordinate.isBlank()) continue;
                String[] parts = coordinate.split(":", -1);
                if (parts.length != 3) throw new IllegalArgumentException("Invalid provider dependency coordinate: " + coordinate);
                var artifact = resolver.resolve(new DefaultArtifact(parts[0], parts[1], "", "jar", parts[2])).getArtifact();
                Path target = providers.resolve(parts[0] + "__" + parts[1] + ".jar");
                Files.copy(artifact.getFile().toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String currentProjectClasses = System.getenv("KCTF_CURRENT_PROJECT_CLASSES");
        if (currentProjectClasses != null && !currentProjectClasses.isBlank()) {
            addDirectoryAsJar(Path.of(currentProjectClasses), providers.resolve("test-project-provider.jar"));
        }
    }

    private static void addDirectoryAsJar(Path classes, Path target) throws Exception {
        if (!Files.isDirectory(classes)) {
            throw new IllegalArgumentException("Current project provider classes do not exist: " + classes);
        }
        try (var paths = Files.walk(classes); var output = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String name = classes.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                output.putNextEntry(new ZipEntry(name));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static void setKeycloakHome(Path home) throws Exception {
        System.setProperty("kc.home.dir", home.toString());
        Class<?> environment = Class.forName("org.keycloak.quarkus.runtime.Environment");
        try {
            environment.getMethod("setHomeDir", Path.class).invoke(null, home);
        } catch (NoSuchMethodException oldKeycloak) {
            // Keycloak 20 reads kc.home.dir directly and has no setter yet.
        }
    }

    private static boolean initSys26(String... args) throws Exception {
        var sourceDirectory = Files.createTempDirectory("kc-noop-picocli-source-");
        var classesDirectory = Files.createTempDirectory("kc-noop-picocli-classes-");
        String source = "package io.github.wadahiro.keycloak.testframework.quarkus.generated;\n"
                + "public final class NoOpPicocli extends org.keycloak.quarkus.runtime.cli.Picocli {\n"
                + " public static int exitCode = Integer.MIN_VALUE;\n"
                + " @Override public void build() {}\n"
                + " @Override public void start() { throw new AssertionError(); }\n"
                + " @Override public void exit(int code) { exitCode = code; }\n"
                + "}\n";
        Path sourceFile = sourceDirectory.resolve("NoOpPicocli.java");
        Files.writeString(sourceFile, source);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath",
                System.getProperty("java.class.path"), "-d", classesDirectory.toString(), sourceFile.toString());
        if (result != 0) throw new IllegalStateException("Unable to compile the Keycloak CLI bootstrap shim");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {classesDirectory.toUri().toURL()}, QuarkusServer.class.getClassLoader())) {
            Class<?> shim = Class.forName("io.github.wadahiro.keycloak.testframework.quarkus.generated.NoOpPicocli", true, loader);
            Object picocli = shim.getConstructor().newInstance();
            shim.getMethod("parseAndRun", List.class).invoke(picocli, List.of(args));
            int exitCode = (int) shim.getField("exitCode").get(null);
            int rebuiltCode = Class.forName("org.keycloak.quarkus.runtime.cli.command.AbstractAutoBuildCommand")
                    .getField("REBUILT_EXIT_CODE").getInt(null);
            System.setProperty("kc.config.built", "true");
            return exitCode == rebuiltCode;
        }
    }
}
