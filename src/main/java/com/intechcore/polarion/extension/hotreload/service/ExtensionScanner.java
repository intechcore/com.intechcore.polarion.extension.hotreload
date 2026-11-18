package com.intechcore.polarion.extension.hotreload.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Compares {@code <install>/extensions/*&#47;eclipse/plugins/*.jar} with the installed bundles, the same folders
 * {@code com.siemens.polarion.eclipse.configurator} scans at startup.
 */
public class ExtensionScanner {

    private static final String REFERENCE_PREFIX = "reference:";
    private static final String FILE_PREFIX = "file:";

    private final BundleContext bundleContext;
    private final Path installDir;
    private final Path extensionsDir;
    private final List<String> warnings = new ArrayList<>();
    private final Set<Path> unreadableJars = new HashSet<>();
    private final Set<String> symbolicNames = new HashSet<>();
    private boolean ownJarChanged;

    public ExtensionScanner(@NotNull BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        this.installDir = installDir();
        this.extensionsDir = installDir.resolve("extensions").normalize();
    }

    @NotNull
    public List<BundleChange> scan(@NotNull String ownSymbolicName) {
        warnings.clear();
        unreadableJars.clear();
        Map<String, Path> jars = extensionJars();
        Map<String, Bundle> installed = installedExtensionBundles();
        ownJarChanged = false;
        symbolicNames.clear();
        symbolicNames.addAll(jars.keySet());
        symbolicNames.addAll(installed.keySet());
        List<BundleChange> changes = new ArrayList<>();

        jars.forEach((symbolicName, jar) -> {
            Bundle bundle = installed.get(symbolicName);
            if (symbolicName.equals(ownSymbolicName)) {
                // This extension cannot replace itself while it runs.
                ownJarChanged = bundle != null && (jar.equals(locationPath(bundle))
                        ? contentChanged(jar, bundle)
                        : !unreadableJars.contains(locationPath(bundle)));
                return;
            }
            if (bundle == null) {
                changes.add(new BundleChange(BundleChange.Type.INSTALL, symbolicName, jar, null));
            } else if (!jar.equals(locationPath(bundle))) {
                // An unreadable jar of the installed bundle, for example while it is copied, does not make an older one win.
                if (!unreadableJars.contains(locationPath(bundle))) {
                    changes.add(new BundleChange(BundleChange.Type.REPLACE, symbolicName, jar, bundle));
                }
            } else if (contentChanged(jar, bundle)) {
                changes.add(new BundleChange(BundleChange.Type.UPDATE, symbolicName, jar, bundle));
            }
        });
        installed.forEach((symbolicName, bundle) -> {
            // A jar that cannot be read now, for example while it is copied, is not a deleted jar.
            if (!jars.containsKey(symbolicName) && !symbolicName.equals(ownSymbolicName) && !unreadableJars.contains(locationPath(bundle))) {
                changes.add(new BundleChange(BundleChange.Type.UNINSTALL, symbolicName, null, bundle));
            }
        });
        return changes;
    }

    /**
     * @return what the last scan found suspicious, such as two jars with one symbolic name
     */
    @NotNull
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * @return whether the last scan found another jar of this extension than the running one
     */
    public boolean isOwnJarChanged() {
        return ownJarChanged;
    }

    /**
     * @return whether the last scan met a jar it could not read, whose symbolic name it therefore does not know
     */
    public boolean hasUnreadableJars() {
        return !unreadableJars.isEmpty();
    }

    /**
     * @return the symbolic names of the jars and installed extension bundles the last scan found
     */
    @NotNull
    public Set<String> getSymbolicNames() {
        return symbolicNames;
    }

    /**
     * @return the jar the bundle was installed from, or {@code null} when it is not a file
     */
    @Nullable
    public Path jarOf(@NotNull Bundle bundle) {
        return locationPath(bundle);
    }

    /**
     * Compares the content with the hash taken at install time. Without one, falls back to the file time, which
     * misses a copy that keeps an old time.
     */
    private boolean contentChanged(@NotNull Path jar, @NotNull Bundle bundle) {
        String installed = JarFingerprints.get(bundle);
        if (installed != null) {
            try {
                return !installed.equals(JarFingerprints.of(jar));
            } catch (IllegalStateException e) {
                // Not an unreadable jar: its manifest gave the symbolic name, so nothing else is in doubt.
                warnings.add("cannot read " + jar.getFileName() + " (" + e.getMessage() + "), skipped");
                return false;
            }
        }
        return jar.toFile().lastModified() > bundle.getLastModified();
    }

    public boolean isExtensionBundle(@NotNull Bundle bundle) {
        Path path = locationPath(bundle);
        return path != null && path.startsWith(extensionsDir);
    }

    @NotNull
    public static String installLocation(@NotNull Path jar) {
        return REFERENCE_PREFIX + jar.toUri();
    }

    /**
     * When two jars carry one symbolic name, the higher Bundle-Version wins, whatever the file names are.
     */
    @NotNull
    private Map<String, Path> extensionJars() {
        Map<String, Path> result = new LinkedHashMap<>();
        Map<String, Version> versions = new HashMap<>();
        if (!Files.isDirectory(extensionsDir)) {
            return result;
        }
        try (Stream<Path> extensions = Files.list(extensionsDir)) {
            for (Path extension : extensions.sorted().toList()) {
                Path plugins = extension.resolve("eclipse").resolve("plugins");
                if (!Files.isDirectory(plugins)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(plugins)) {
                    for (Path jar : files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                        Manifest manifest = bundleManifest(jar);
                        if (manifest == null) {
                            continue;
                        }
                        String symbolicName = manifest.getMainAttributes().getValue("Bundle-SymbolicName").split(";")[0].trim();
                        Version version = version(manifest);
                        Path known = result.get(symbolicName);
                        if (known == null || version.compareTo(versions.get(symbolicName)) > 0) {
                            if (known != null) {
                                warnings.add(duplicate(symbolicName, known, jar, jar));
                            }
                            result.put(symbolicName, jar.normalize());
                            versions.put(symbolicName, version);
                        } else {
                            warnings.add(duplicate(symbolicName, known, jar, known));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list " + extensionsDir, e);
        }
        return result;
    }

    @NotNull
    private Map<String, Bundle> installedExtensionBundles() {
        Map<String, Bundle> result = new HashMap<>();
        for (Bundle bundle : bundleContext.getBundles()) {
            if (bundle.getSymbolicName() != null && bundle.getState() != Bundle.UNINSTALLED && isExtensionBundle(bundle)) {
                result.put(bundle.getSymbolicName(), bundle);
            }
        }
        return result;
    }

    @Nullable
    private Path locationPath(@NotNull Bundle bundle) {
        String location = bundle.getLocation();
        if (location.startsWith(REFERENCE_PREFIX)) {
            location = location.substring(REFERENCE_PREFIX.length());
        }
        if (!location.startsWith(FILE_PREFIX)) {
            return null;
        }
        try {
            URI uri = new URI(location);
            if (uri.isOpaque()) {
                // relative form "file:extensions/..." written by simpleconfigurator for paths under the install area
                return installDir.resolve(uri.getSchemeSpecificPart()).normalize();
            }
            return Path.of(uri).normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return installDir.resolve(location.substring(FILE_PREFIX.length())).normalize();
        }
    }

    @NotNull
    private static String duplicate(@NotNull String symbolicName, @NotNull Path first, @NotNull Path second, @NotNull Path used) {
        return "two jars for " + symbolicName + ": " + first.getFileName() + " and " + second.getFileName()
                + ", using " + used.getFileName() + "; remove the other one";
    }

    /**
     * @return the manifest of a bundle jar, or {@code null} for a source bundle, a plain jar or an unreadable file
     */
    @Nullable
    private Manifest bundleManifest(@NotNull Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null || manifest.getMainAttributes().getValue("Eclipse-SourceBundle") != null
                    || manifest.getMainAttributes().getValue("Bundle-SymbolicName") == null) {
                return null;
            }
            return manifest;
        } catch (IOException e) {
            unreadableJars.add(jar.normalize());
            warnings.add("cannot read " + jar.getFileName() + " (" + e.getMessage() + "), skipped");
            return null;
        }
    }

    @NotNull
    private static Version version(@NotNull Manifest manifest) {
        try {
            return Version.parseVersion(manifest.getMainAttributes().getValue("Bundle-Version"));
        } catch (IllegalArgumentException e) {
            return Version.emptyVersion;
        }
    }

    @NotNull
    private static Path installDir() {
        String installArea = System.getProperty("osgi.install.area");
        if (installArea == null) {
            throw new IllegalStateException("System property osgi.install.area is not set");
        }
        try {
            return Path.of(new URI(installArea.replace(" ", "%20"))).normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return new File(installArea.replaceFirst("^file:", "")).toPath().normalize();
        }
    }
}
