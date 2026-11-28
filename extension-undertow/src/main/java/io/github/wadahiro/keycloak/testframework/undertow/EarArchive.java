package io.github.wadahiro.keycloak.testframework.undertow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The provider members of an enterprise archive, laid out as WildFly deploys an EAR: its top-level JAR modules
 * ({@code <ejb>} and {@code <java>} modules of {@code META-INF/application.xml}, or every top-level {@code .jar} without
 * one) and the JARs directly in its library directory ({@code <library-directory>}, {@code lib} by default).
 * An EAR file is expanded into a directory; an exploded EAR is used in place.
 */
final class EarArchive {
    private EarArchive() { }

    /** Returns the module JARs (in order) followed by the library JARs (in file-name order). */
    static List<Path> members(Path ear, Path expandInto) throws IOException {
        if (Files.isDirectory(ear)) return layout(ear, ear);
        Files.createDirectories(expandInto);
        Path root = expandInto.toAbsolutePath().normalize();
        try (JarFile archive = new JarFile(ear.toFile())) {
            for (JarEntry entry : (Iterable<JarEntry>) archive.stream()::iterator) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) throw new IOException("EAR entry escapes the expansion directory: " + entry.getName() + " in " + ear);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = archive.getInputStream(entry)) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
                }
            }
        }
        return layout(root, ear);
    }

    private static List<Path> layout(Path root, Path ear) throws IOException {
        Path descriptor = root.resolve("META-INF/application.xml");
        String libraryDirectory = "lib";
        List<Path> modules = new ArrayList<>();
        if (Files.isRegularFile(descriptor)) {
            Element application = parse(descriptor, ear);
            NodeList libraries = application.getElementsByTagNameNS("*", "library-directory");
            if (libraries.getLength() > 0) libraryDirectory = libraries.item(0).getTextContent().trim();
            NodeList declared = application.getElementsByTagNameNS("*", "module");
            for (int i = 0; i < declared.getLength(); i++) {
                for (Node child = declared.item(i).getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                    String type = child.getLocalName();
                    if (type.equals("alt-dd")) continue;
                    if (!type.equals("ejb") && !type.equals("java")) {
                        throw new IllegalArgumentException("Unsupported EAR module type <" + type + "> in " + ear
                                + "; only <ejb> and <java> JAR modules are deployed as Keycloak providers");
                    }
                    Path module = root.resolve(child.getTextContent().trim()).normalize();
                    if (!module.startsWith(root) || !Files.exists(module)) {
                        throw new IllegalArgumentException("EAR module " + child.getTextContent().trim() + " is missing in " + ear);
                    }
                    modules.add(module);
                }
            }
        } else {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".war") || name.endsWith(".rar")) {
                        throw new IllegalArgumentException("Unsupported EAR module " + entry.getFileName() + " in " + ear
                                + "; only JAR modules are deployed as Keycloak providers");
                    }
                    if (name.endsWith(".jar") && !entry.getFileName().toString().equals(libraryDirectory)) modules.add(entry);
                }
            }
            modules.sort(null);
        }
        List<Path> libraries = new ArrayList<>();
        Path library = libraryDirectory.isEmpty() ? null : root.resolve(libraryDirectory).normalize();
        if (library != null && library.startsWith(root) && Files.isDirectory(library)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(library, "*.jar")) {
                for (Path entry : entries) libraries.add(entry);
            }
            libraries.sort(null);
        }
        modules.removeAll(libraries);
        if (modules.isEmpty() && libraries.isEmpty()) throw new IllegalArgumentException("EAR contains no JAR module or library: " + ear);
        List<Path> members = new ArrayList<>(modules);
        members.addAll(libraries);
        return members;
    }

    private static Element parse(Path descriptor, Path ear) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(descriptor.toFile()).getDocumentElement();
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot read META-INF/application.xml of " + ear, failure);
        }
    }
}
