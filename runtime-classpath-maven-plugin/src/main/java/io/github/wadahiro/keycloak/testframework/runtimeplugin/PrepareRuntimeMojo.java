package io.github.wadahiro.keycloak.testframework.runtimeplugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;

/** Writes a test-classpath descriptor from the selected runtime dependency graph. */
@Mojo(name = "prepare", threadSafe = true)
public final class PrepareRuntimeMojo extends AbstractMojo {
    private static final String DESCRIPTOR = "META-INF/keycloak-test-runtime.properties";
    private static final String PLUGIN_ARTIFACT_ID = "keycloak-test-runtime-maven-plugin";

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repositorySession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepositories;

    @Parameter(defaultValue = "${project.build.plugins}", readonly = true, required = true)
    private List<Plugin> buildPlugins;

    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
    private File testOutputDirectory;

    @Parameter(property = "kc.test.runtime.version")
    private String requestedVersion;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            Dependency runtimeDependency = runtimeDependency();
            Artifact rootArtifact = new DefaultArtifact(runtimeDependency.getGroupId(), runtimeDependency.getArtifactId(),
                    runtimeDependency.getClassifier(), "jar", runtimeDependency.getVersion());
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRoot(new org.eclipse.aether.graph.Dependency(rootArtifact, "runtime"));
            collectRequest.setRepositories(remoteRepositories);
            DependencyNode root = repositorySystem.collectDependencies(repositorySession, collectRequest).getRoot();
            List<ArtifactResult> resolved = repositorySystem.resolveDependencies(repositorySession, new DependencyRequest(root, null)).getArtifactResults();

            List<File> jars = new ArrayList<>();
            Properties runtime = null;
            File runtimeJar = null;
            for (ArtifactResult result : resolved) {
                Artifact artifact = result.getArtifact();
                File file = artifact.getFile();
                if (file == null || !file.isFile() || !file.getName().endsWith(".jar")) continue;
                File canonical = file.getCanonicalFile();
                if (!jars.contains(canonical)) jars.add(canonical);
                Properties candidate = readDescriptor(canonical);
                if (candidate != null) {
                    if (runtime != null) throw new MojoExecutionException("Configure exactly one Keycloak runtime artifact as a dependency of keycloak-test-runtime-maven-plugin.");
                    runtime = candidate;
                    runtimeJar = canonical;
                }
            }
            if (runtime == null) {
                throw new MojoExecutionException("The configured runtime dependency has no " + DESCRIPTOR + " metadata.");
            }
            if (requestedVersion != null && !requestedVersion.equals(runtime.getProperty("version"))) {
                throw new MojoExecutionException("Configured runtime " + runtime.getProperty("version") + " does not match kc.test.runtime.version=" + requestedVersion + ". Select the matching plugin dependency.");
            }
            jars.remove(runtimeJar);
            jars.add(0, runtimeJar);
            Properties prepared = new Properties();
            prepared.putAll(runtime);
            prepared.setProperty("classpath", String.join(File.pathSeparator, jars.stream().map(File::getPath).toList()));
            File output = new File(testOutputDirectory, DESCRIPTOR);
            Files.createDirectories(output.toPath().getParent());
            try (FileOutputStream stream = new FileOutputStream(output)) {
                prepared.store(stream, "Generated by keycloak-test-runtime:prepare; do not edit");
            }
            getLog().info("Prepared Keycloak " + runtime.getProperty("version") + " runtime classpath in " + output);
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Could not prepare the isolated Keycloak runtime classpath", e);
        }
    }

    private Dependency runtimeDependency() throws MojoExecutionException {
        List<Dependency> matches = new ArrayList<>();
        for (Plugin plugin : buildPlugins) {
            if (!PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId())) continue;
            for (Dependency dependency : plugin.getDependencies()) {
                if (dependency.getArtifactId() != null && dependency.getArtifactId().startsWith("keycloak-test-runtime-")) {
                    matches.add(dependency);
                }
            }
        }
        if (matches.size() != 1) {
            throw new MojoExecutionException("Configure exactly one keycloak-test-runtime-* artifact as a dependency of keycloak-test-runtime-maven-plugin.");
        }
        Dependency dependency = matches.get(0);
        if (dependency.getVersion() == null || dependency.getVersion().contains("${")) {
            throw new MojoExecutionException("The runtime plugin dependency version must be resolved by Maven before prepare runs.");
        }
        return dependency;
    }

    private Properties readDescriptor(File jar) throws IOException {
        try (JarFile archive = new JarFile(jar)) {
            var entry = archive.getJarEntry(DESCRIPTOR);
            if (entry == null) return null;
            Properties properties = new Properties();
            try (var stream = archive.getInputStream(entry)) {
                properties.load(stream);
            }
            return properties;
        }
    }
}
