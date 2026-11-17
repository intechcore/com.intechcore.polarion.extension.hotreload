package com.intechcore.polarion.extension.hotreload.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public ExtensionScanner(@NotNull BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        this.installDir = installDir();
        this.extensionsDir = installDir.resolve("extensions").normalize();
    }

    @NotNull
    public List<BundleChange> scan(@NotNull String ownSymbolicName) {
        Map<String, Path> jars = extensionJars();
        Map<String, Bundle> installed = installedExtensionBundles();
        List<BundleChange> changes = new ArrayList<>();

        jars.forEach((symbolicName, jar) -> {
            if (symbolicName.equals(ownSymbolicName)) {
                return;
            }
            Bundle bundle = installed.get(symbolicName);
            if (bundle == null) {
                changes.add(new BundleChange(BundleChange.Type.INSTALL, symbolicName, jar, null));
            } else if (!jar.equals(locationPath(bundle))) {
                changes.add(new BundleChange(BundleChange.Type.REPLACE, symbolicName, jar, bundle));
            } else if (jar.toFile().lastModified() > bundle.getLastModified()) {
                changes.add(new BundleChange(BundleChange.Type.UPDATE, symbolicName, jar, bundle));
            }
        });
        installed.forEach((symbolicName, bundle) -> {
            if (!jars.containsKey(symbolicName) && !symbolicName.equals(ownSymbolicName)) {
                changes.add(new BundleChange(BundleChange.Type.UNINSTALL, symbolicName, null, bundle));
            }
        });
        return changes;
    }

    public boolean isExtensionBundle(@NotNull Bundle bundle) {
        Path path = locationPath(bundle);
        return path != null && path.startsWith(extensionsDir);
    }

    @NotNull
    public static String installLocation(@NotNull Path jar) {
        return REFERENCE_PREFIX + jar.toUri();
    }

    @NotNull
    private Map<String, Path> extensionJars() {
        Map<String, Path> result = new LinkedHashMap<>();
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
                        String symbolicName = symbolicName(jar);
                        if (symbolicName != null) {
                            result.put(symbolicName, jar.normalize());
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

    @Nullable
    private static String symbolicName(@NotNull Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null || manifest.getMainAttributes().getValue("Eclipse-SourceBundle") != null) {
                return null;
            }
            String name = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
            return name == null ? null : name.split(";")[0].trim();
        } catch (IOException e) {
            return null;
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
