package com.intechcore.polarion.extension.hotreload.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExtensionScannerTest {

    private static final String INSTALL_AREA = "osgi.install.area";
    private static final String SELF = "my.self";

    @TempDir
    Path installDir;

    private String previousInstallArea;
    private BundleContext bundleContext;

    @BeforeEach
    void setUp() {
        previousInstallArea = System.getProperty(INSTALL_AREA);
        System.setProperty(INSTALL_AREA, installDir.toUri().toString());
        bundleContext = mock(BundleContext.class);
        when(bundleContext.getBundles()).thenReturn(new Bundle[0]);
    }

    @AfterEach
    void tearDown() {
        if (previousInstallArea == null) {
            System.clearProperty(INSTALL_AREA);
        } else {
            System.setProperty(INSTALL_AREA, previousInstallArea);
        }
    }

    @Test
    void findsNothingWithoutAnExtensionsFolder() {
        assertThat(new ExtensionScanner(bundleContext).scan(SELF)).isEmpty();
    }

    @Test
    void installsAJarThatDoesNotRunYet() throws IOException {
        Path jar = jar("ext", "ext-1.0.jar", "my.ext;singleton:=true", false);

        List<BundleChange> changes = new ExtensionScanner(bundleContext).scan(SELF);

        assertThat(changes).containsExactly(new BundleChange(BundleChange.Type.INSTALL, "my.ext", jar.normalize(), null));
        assertThat(changes.get(0)).hasToString("INSTALL my.ext (ext-1.0.jar)");
    }

    @Test
    void updatesABundleWhoseJarIsNewer() throws IOException {
        Path jar = jar("ext", "ext-1.0.jar", "my.ext", false);
        Bundle bundle = bundle("my.ext", ExtensionScanner.installLocation(jar), jar.toFile().lastModified() - 1000);

        assertThat(new ExtensionScanner(bundleContext).scan(SELF))
                .containsExactly(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jar.normalize(), bundle));
    }

    @Test
    void leavesAnUnchangedBundleAlone() throws IOException {
        Path jar = jar("ext", "ext-1.0.jar", "my.ext", false);
        bundle("my.ext", ExtensionScanner.installLocation(jar), jar.toFile().lastModified());

        assertThat(new ExtensionScanner(bundleContext).scan(SELF)).isEmpty();
    }

    @Test
    void replacesABundleWhoseJarHasANewName() throws IOException {
        Path oldJar = installDir.resolve("extensions/ext/eclipse/plugins/ext-1.0.jar");
        Path newJar = jar("ext", "ext-2.0.jar", "my.ext", false);
        Bundle bundle = bundle("my.ext", ExtensionScanner.installLocation(oldJar), 0);

        assertThat(new ExtensionScanner(bundleContext).scan(SELF))
                .containsExactly(new BundleChange(BundleChange.Type.REPLACE, "my.ext", newJar.normalize(), bundle));
    }

    @Test
    void uninstallsABundleWhoseJarIsGone() {
        Bundle bundle = bundle("my.ext", "reference:file:extensions/ext/eclipse/plugins/ext-1.0.jar", 0);

        List<BundleChange> changes = new ExtensionScanner(bundleContext).scan(SELF);

        assertThat(changes).containsExactly(new BundleChange(BundleChange.Type.UNINSTALL, "my.ext", null, bundle));
        assertThat(changes.get(0)).hasToString("UNINSTALL my.ext");
    }

    @Test
    void skipsItselfSourceBundlesAndJarsWithoutAName() throws IOException {
        jar("self", "self.jar", SELF, false);
        jar("ext", "ext.source.jar", "my.ext.source", true);
        jar("ext", "nameless.jar", null, false);
        Files.writeString(installDir.resolve("extensions/ext/eclipse/plugins/broken.jar"), "not a jar");
        Files.writeString(installDir.resolve("extensions/ext/eclipse/plugins/readme.txt"), "text");
        Files.createDirectories(installDir.resolve("extensions/no-plugins"));
        bundle(SELF, "reference:file:extensions/self/eclipse/plugins/old-self.jar", 0);

        assertThat(new ExtensionScanner(bundleContext).scan(SELF)).isEmpty();
    }

    @Test
    void ignoresBundlesOutsideTheExtensionsFolder() {
        Bundle platform = bundle("com.polarion.platform", "reference:file:plugins/platform.jar", 0);
        Bundle remote = bundle("remote", "http://example.com/remote.jar", 0);
        Bundle malformed = bundle("malformed", "file:extensions/a b/x.jar", 0);
        Bundle uninstalled = bundle("gone", "file:extensions/gone/eclipse/plugins/gone.jar", 0);
        when(uninstalled.getState()).thenReturn(Bundle.UNINSTALLED);

        ExtensionScanner scanner = new ExtensionScanner(bundleContext);

        assertThat(scanner.isExtensionBundle(platform)).isFalse();
        assertThat(scanner.isExtensionBundle(remote)).isFalse();
        assertThat(scanner.isExtensionBundle(malformed)).isTrue();
        assertThat(scanner.scan(SELF)).extracting(BundleChange::symbolicName).containsExactly("malformed");
    }

    @Test
    void failsWithoutTheInstallArea() {
        System.clearProperty(INSTALL_AREA);

        assertThatThrownBy(() -> new ExtensionScanner(bundleContext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(INSTALL_AREA);
    }

    @Test
    void acceptsAnInstallAreaThatIsNotAUri() {
        System.setProperty(INSTALL_AREA, "file:" + installDir);

        assertThat(new ExtensionScanner(bundleContext).scan(SELF)).isEmpty();
    }

    private Path jar(String extension, String fileName, String symbolicName, boolean sourceBundle) throws IOException {
        Path plugins = Files.createDirectories(installDir.resolve("extensions").resolve(extension).resolve("eclipse/plugins"));
        Path jar = plugins.resolve(fileName);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (symbolicName != null) {
            manifest.getMainAttributes().putValue("Bundle-SymbolicName", symbolicName);
        }
        if (sourceBundle) {
            manifest.getMainAttributes().putValue("Eclipse-SourceBundle", "my.ext");
        }
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            return jar;
        }
    }

    private Bundle bundle(String symbolicName, String location, long lastModified) {
        Bundle bundle = mock(Bundle.class);
        when(bundle.getSymbolicName()).thenReturn(symbolicName);
        when(bundle.getLocation()).thenReturn(location);
        when(bundle.getLastModified()).thenReturn(lastModified);
        when(bundle.getState()).thenReturn(Bundle.ACTIVE);
        Bundle[] previous = bundleContext.getBundles();
        Bundle[] all = Arrays.copyOf(previous, previous.length + 1);
        all[previous.length] = bundle;
        when(bundleContext.getBundles()).thenReturn(all);
        return bundle;
    }
}
