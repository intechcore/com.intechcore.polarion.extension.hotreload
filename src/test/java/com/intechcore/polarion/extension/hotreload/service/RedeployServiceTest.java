package com.intechcore.polarion.extension.hotreload.service;

import com.intechcore.polarion.extension.hotreload.descriptor.ExtensionDescriptor;
import com.intechcore.polarion.extension.hotreload.inject.ContributionInjector;
import com.intechcore.polarion.extension.hotreload.inject.TomcatDeployer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedeployServiceTest {

    private static final String PLUGIN_XML = """
            <plugin>
                <extension point="com.polarion.portal.tomcat.webapps">
                    <webapp name="polarion/%s" contextRoot="webapp/%s"/>
                </extension>
            </plugin>
            """;
    private static final String HIVEMODULE = """
            <module id="%s" version="1.0.0">
                <contribution configuration-id="com.polarion.xray.webui.administrationPageExtenders">
                    <extender id="about" name="About" parentNodeId="%s"/>
                </contribution>
            </module>
            """;

    @TempDir
    Path tempDir;

    private final Map<Bundle, Integer> states = new HashMap<>();
    private final List<Bundle> allBundles = new ArrayList<>();
    private List<BundleChange> changes = List.of();
    private final List<Bundle> nonExtensionBundles = new ArrayList<>();
    private Collection<Bundle> closure = List.of();
    private FrameworkEvent refreshOutcome;
    private boolean tomcatAccepts = true;

    private MockedStatic<FrameworkUtil> frameworkUtil;
    private MockedConstruction<ExtensionScanner> scanners;
    private MockedConstruction<TomcatDeployer> tomcats;
    private MockedConstruction<ContributionInjector> injectors;
    private BundleContext context;
    private FrameworkWiring frameworkWiring;
    private Bundle self;

    @BeforeEach
    void setUp() {
        context = mock(BundleContext.class);
        frameworkWiring = mock(FrameworkWiring.class);
        Bundle systemBundle = mock(Bundle.class);
        when(context.getBundle(0)).thenReturn(systemBundle);
        when(systemBundle.adapt(FrameworkWiring.class)).thenReturn(frameworkWiring);
        when(context.getBundles()).thenAnswer(invocation -> allBundles.toArray(new Bundle[0]));
        when(frameworkWiring.getDependencyClosure(anyCollection())).thenAnswer(invocation -> closure);
        doAnswer(invocation -> {
            FrameworkListener listener = invocation.getArgument(1);
            Bundle first = invocation.<Collection<Bundle>>getArgument(0).iterator().next();
            listener.frameworkEvent(refreshOutcome != null ? refreshOutcome : new FrameworkEvent(FrameworkEvent.PACKAGES_REFRESHED, first, null));
            return null;
        }).when(frameworkWiring).refreshBundles(anyCollection(), any(FrameworkListener[].class));
        when(frameworkWiring.resolveBundles(anyCollection())).thenReturn(true);

        self = mock(Bundle.class);
        when(self.getSymbolicName()).thenReturn("my.self");
        when(self.getBundleContext()).thenReturn(context);
        frameworkUtil = mockStatic(FrameworkUtil.class);
        frameworkUtil.when(() -> FrameworkUtil.getBundle(RedeployService.class)).thenReturn(self);

        scanners = mockConstruction(ExtensionScanner.class, (scanner, ctx) -> {
            when(scanner.scan("my.self")).thenAnswer(invocation -> changes);
            when(scanner.isExtensionBundle(any())).thenAnswer(invocation -> {
                Bundle bundle = invocation.getArgument(0);
                return bundle != self && !nonExtensionBundles.contains(bundle);
            });
        });
        tomcats = mockConstruction(TomcatDeployer.class, (tomcat, ctx) -> {
            when(tomcat.remove(anyString())).thenReturn(true);
            when(tomcat.add(any(), anyString())).thenAnswer(invocation -> tomcatAccepts);
        });
        injectors = mockConstruction(ContributionInjector.class);
    }

    @AfterEach
    void tearDown() {
        injectors.close();
        tomcats.close();
        scanners.close();
        frameworkUtil.close();
        allBundles.forEach(DescriptorCache::remove);
    }

    @Test
    void aDryRunListsTheChangesAndTouchesNothing() throws Exception {
        Bundle bundle = extension("my.ext", 10);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jarPath("my.ext.jar"), bundle));

        RescanReport report = new RedeployService().rescan(false);

        assertThat(report.applied()).isFalse();
        assertThat(report.changes()).containsExactly("UPDATE my.ext (my.ext.jar)");
        assertThat(report.log()).isEmpty();
        verify(bundle, never()).update();
        assertThat(tomcats.constructed()).isEmpty();
    }

    @Test
    void reportsThatNothingChanged() {
        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.applied()).isFalse();
        assertThat(report.changes()).isEmpty();
        assertThat(report.log()).containsExactly("nothing changed");
    }

    @Test
    void updatesABundleAndItsDependentsAndSwapsTheirContributions() throws Exception {
        Bundle updated = extension("my.ext", 10);
        Bundle dependent = extension("my.dependent", 11);
        when(dependent.getHeaders()).thenReturn(headers("Bundle-ActivationPolicy", "lazy"));
        Bundle organizer = extension("com.intechcore.polarion.extension.admin-menu-organizer", 12);
        closure = List.of(updated, dependent);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jarPath("my.ext.jar"), updated));
        DescriptorCache.put(updated, new ExtensionDescriptor());

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).isEmpty();
        assertThat(report.applied()).isTrue();
        verify(updated).update();
        verify(dependent).start(Bundle.START_ACTIVATION_POLICY);
        assertThat(report.log()).contains(
                "dependent bundles refreshed too: [my.dependent]",
                "WARNING my.dependent: no descriptor snapshot, reading the current files",
                "context /polarion/my.dependent removed",
                "my.ext: updated in place",
                "refresh finished",
                "context /polarion/my.ext added",
                "context /polarion/my.dependent added",
                "admin-menu-organizer contexts restarted to re-apply the menu order");
        TomcatDeployer tomcat = tomcats.constructed().get(0);
        verify(tomcat).remove("polarion/com.intechcore.polarion.extension.admin-menu-organizer");
        ContributionInjector injector = injectors.constructed().get(0);
        verify(injector).add(any(), eq(updated));
        verify(injector).add(any(), eq(dependent));
        assertThat(DescriptorCache.get(updated).getWebapps()).hasSize(1);
    }

    @Test
    void installsReplacesAndUninstalls() throws Exception {
        Path newJar = jarPath("my.new-1.0.jar");
        Path replacementJar = jarPath("my.replaced-2.0.jar");
        Bundle installed = extension("my.new", 20);
        Bundle replacement = extension("my.replaced", 21);
        Bundle replaced = extension("my.replaced", 22);
        Bundle uninstalled = extension("my.gone", 23);
        when(context.installBundle(ExtensionScanner.installLocation(newJar))).thenReturn(installed);
        when(context.installBundle(ExtensionScanner.installLocation(replacementJar))).thenReturn(replacement);
        doAnswer(invocation -> states.put(uninstalled, Bundle.UNINSTALLED)).when(uninstalled).uninstall();
        doAnswer(invocation -> states.put(replaced, Bundle.UNINSTALLED)).when(replaced).uninstall();
        closure = List.of(replaced, uninstalled);
        changes = List.of(
                new BundleChange(BundleChange.Type.INSTALL, "my.new", newJar, null),
                new BundleChange(BundleChange.Type.REPLACE, "my.replaced", replacementJar, replaced),
                new BundleChange(BundleChange.Type.UNINSTALL, "my.gone", null, uninstalled));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).isEmpty();
        assertThat(report.log()).contains(
                "my.new: installed",
                "my.replaced: replaced by my.replaced-2.0.jar",
                "my.gone: uninstalled",
                "context /polarion/my.new added",
                "context /polarion/my.replaced added");
        verify(installed.adapt(BundleStartLevel.class)).setStartLevel(4);
        assertThat(DescriptorCache.get(uninstalled)).isNull();
        assertThat(DescriptorCache.get(installed)).isNotNull();
    }

    @Test
    void readsTheOldDescriptorsFromTheNewJarWhenTheOldOnesAreUnreadable() throws Exception {
        Bundle bundle = extension("my.ext", 10);
        when(bundle.getEntry("plugin.xml")).thenReturn(Path.of(tempDir.toString(), "missing.xml").toUri().toURL());
        Path jar = realJar("my.ext-2.0.jar", "my.ext");
        closure = List.of(bundle);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jar, bundle));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.log()).anyMatch(line -> line.startsWith("WARNING my.ext: old descriptors unreadable"));
        assertThat(report.log()).contains("context /polarion/my.ext-from-jar removed");
    }

    @Test
    void reportsRestartReasonsWarningsAndRejectedContexts() throws Exception {
        Bundle bundle = extension("my.ext", 10);
        when(bundle.getEntry("plugin.xml")).thenReturn(file("restart-plugin.xml", """
                <plugin>
                    <extension point="com.polarion.portal.tomcat.webapps">
                        <webapp name="polarion/my.ext" contextRoot="webapp/my.ext" errorReporter="x"/>
                    </extension>
                    <extension point="other.point"/>
                </plugin>
                """));
        when(bundle.getEntry("META-INF/hivemodule.xml")).thenReturn(null);
        when(bundle.getHeaders()).thenReturn(headers("Guice-Modules", "my.Module"));
        tomcatAccepts = false;
        closure = List.of(bundle);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jarPath("my.ext.jar"), bundle));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.restartRequired()).containsExactly(
                "my.ext: Guice-Modules header (my.Module): the Guice injector is built once at startup");
        assertThat(report.log()).contains(
                "WARNING my.ext: plugin.xml extension point other.point is not refreshed",
                "WARNING context /polarion/my.ext: errorReporter is not re-registered",
                "context /polarion/my.ext NOT added, see the Polarion log");
    }

    @Test
    void refusesARefreshThatReachesANonExtensionBundle() throws Exception {
        Bundle bundle = extension("my.ext", 10);
        Bundle platform = extension("com.polarion.platform", 1);
        nonExtensionBundles.add(platform);
        closure = List.of(bundle, platform);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jarPath("my.ext.jar"), bundle));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.applied()).isTrue();
        assertThat(report.errors()).containsExactly("IllegalStateException: Refresh would reach com.polarion.platform, "
                + "which is not an extension bundle; restart Polarion instead");
        verify(bundle, never()).update();
    }

    @Test
    void reportsAFailedRefresh() throws Exception {
        Bundle bundle = extension("my.ext", 10);
        closure = List.of(bundle);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jarPath("my.ext.jar"), bundle));
        refreshOutcome = new FrameworkEvent(FrameworkEvent.ERROR, bundle, new IllegalArgumentException("wiring"));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).containsExactly("IllegalStateException: Bundle refresh failed: wiring");
    }

    @Test
    void reportsABundleThatDoesNotResolve() throws Exception {
        Path jar = jarPath("my.new.jar");
        Bundle installed = extension("my.new", 20);
        states.put(installed, Bundle.INSTALLED);
        when(context.installBundle(anyString())).thenReturn(installed);
        changes = List.of(new BundleChange(BundleChange.Type.INSTALL, "my.new", jar, null));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).containsExactly(
                "IllegalStateException: my.new does not resolve; check its Require-Bundle and Import-Package");
    }

    @Test
    void keepsTheInterruptFlagWhenARescanIsInterrupted() throws Exception {
        Bundle bundle = extension("my.ext", 10);
        closure = List.of(bundle);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jarPath("my.ext.jar"), bundle));
        doAnswer(invocation -> null).when(frameworkWiring).refreshBundles(anyCollection(), any(FrameworkListener[].class));

        Thread.currentThread().interrupt();
        RescanReport report = new RedeployService().rescan(true);

        assertThat(Thread.interrupted()).isTrue();
        assertThat(report.errors()).singleElement().asString().startsWith("InterruptedException");
    }

    @Test
    void snapshotsTheDescriptorsOfEveryInstalledExtension() throws Exception {
        allBundles.add(self);
        Bundle extension = extension("my.ext", 10);
        Bundle broken = extension("my.broken", 11);
        when(broken.getEntry("plugin.xml")).thenReturn(file("broken.xml", "<plugin>"));
        Bundle gone = extension("my.gone", 12);
        states.put(gone, Bundle.UNINSTALLED);

        try (MockedStatic<ContributionInjector> lazy = mockStatic(ContributionInjector.class)) {
            lazy.when(ContributionInjector::buildLazyServices).thenThrow(new IllegalStateException("no tracker"));

            new RedeployService().snapshotInstalledExtensions();
        }

        assertThat(DescriptorCache.get(extension)).isNotNull();
        assertThat(DescriptorCache.get(broken)).isNull();
        assertThat(DescriptorCache.get(gone)).isNull();
        assertThat(DescriptorCache.get(self)).isNull();
    }

    private Bundle extension(String symbolicName, long id) throws IOException {
        Bundle bundle = mock(Bundle.class);
        when(bundle.getSymbolicName()).thenReturn(symbolicName);
        when(bundle.getBundleId()).thenReturn(id);
        states.put(bundle, Bundle.RESOLVED);
        when(bundle.getState()).thenAnswer(invocation -> states.get(bundle));
        when(bundle.getHeaders()).thenReturn(new Hashtable<>());
        when(bundle.getEntry("plugin.xml")).thenReturn(file(symbolicName + "-plugin.xml", PLUGIN_XML.formatted(symbolicName, symbolicName)));
        when(bundle.getEntry("META-INF/hivemodule.xml")).thenReturn(file(symbolicName + "-hivemodule.xml", HIVEMODULE.formatted(symbolicName, symbolicName)));
        BundleWiring wiring = mock(BundleWiring.class);
        when(wiring.getClassLoader()).thenReturn(getClass().getClassLoader());
        when(bundle.adapt(BundleWiring.class)).thenReturn(wiring);
        when(bundle.adapt(BundleStartLevel.class)).thenReturn(mock(BundleStartLevel.class));
        allBundles.add(bundle);
        return bundle;
    }

    private static Hashtable<String, String> headers(String name, String value) {
        Hashtable<String, String> headers = new Hashtable<>();
        headers.put(name, value);
        return headers;
    }

    private URL file(String name, String content) throws IOException {
        return Files.writeString(tempDir.resolve(name), content).toUri().toURL();
    }

    private Path jarPath(String name) {
        return tempDir.resolve(name);
    }

    private Path realJar(String name, String symbolicName) throws IOException {
        Path jar = tempDir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Bundle-SymbolicName", symbolicName);
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new JarEntry("plugin.xml"));
            jarOut.write(PLUGIN_XML.formatted(symbolicName + "-from-jar", symbolicName).getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }
}
