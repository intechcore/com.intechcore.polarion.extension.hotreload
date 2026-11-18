package com.intechcore.polarion.extension.hotreload.service;

import com.intechcore.polarion.extension.hotreload.descriptor.ExtensionDescriptor;
import com.intechcore.polarion.extension.hotreload.inject.ContributionInjector;
import com.intechcore.polarion.extension.hotreload.inject.PendingWorkflowHolders;
import com.intechcore.polarion.extension.hotreload.inject.TomcatDeployer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
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
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private FrameworkEvent refreshError;
    private final List<FrameworkListener> frameworkListeners = new ArrayList<>();
    private final List<String> scannerWarnings = new ArrayList<>();
    private final Set<String> scannedNames = new HashSet<>();
    private boolean unreadableJars;
    private boolean ownJarChanged;
    private boolean selfInExtensions;
    private final Map<Bundle, Path> bundleJars = new HashMap<>();
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
        doAnswer(invocation -> frameworkListeners.add(invocation.getArgument(0))).when(context).addFrameworkListener(any());
        doAnswer(invocation -> frameworkListeners.remove(invocation.<FrameworkListener>getArgument(0))).when(context).removeFrameworkListener(any());
        // As Equinox does: errors go to the framework listeners, the refresh listener gets PACKAGES_REFRESHED only.
        doAnswer(invocation -> {
            FrameworkListener listener = invocation.getArgument(1);
            Bundle first = invocation.<Collection<Bundle>>getArgument(0).iterator().next();
            if (refreshError != null) {
                List.copyOf(frameworkListeners).forEach(l -> l.frameworkEvent(refreshError));
            }
            listener.frameworkEvent(new FrameworkEvent(FrameworkEvent.PACKAGES_REFRESHED, first, null));
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
            when(scanner.getWarnings()).thenAnswer(invocation -> scannerWarnings);
            when(scanner.getSymbolicNames()).thenAnswer(invocation -> scannedNames);
            when(scanner.hasUnreadableJars()).thenAnswer(invocation -> unreadableJars);
            when(scanner.isOwnJarChanged()).thenAnswer(invocation -> ownJarChanged);
            when(scanner.jarOf(any())).thenAnswer(invocation -> bundleJars.get(invocation.<Bundle>getArgument(0)));
            when(scanner.isExtensionBundle(any())).thenAnswer(invocation -> {
                Bundle bundle = invocation.getArgument(0);
                return (bundle != self || selfInExtensions) && !nonExtensionBundles.contains(bundle);
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
        allBundles.forEach(JarFingerprints::remove);
        PendingWorkflowHolders.clear();
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
        verify(injector).remove(any(), eq("my.ext"), any());
        verify(injector).remove(any(), eq("my.dependent"), any());
        verify(injector).add(any(), eq(updated));
        verify(injector).add(any(), eq(dependent));
        verify(injector).finish(Set.of());
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
        Files.writeString(newJar, "new content");
        bundleJars.put(installed, newJar);
        JarFingerprints.put(uninstalled, newJar);
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
        assertThat(JarFingerprints.get(installed)).isEqualTo(JarFingerprints.of(newJar));
        assertThat(JarFingerprints.get(uninstalled)).isNull();
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
        refreshError = new FrameworkEvent(FrameworkEvent.ERROR, bundle, new IllegalArgumentException("wiring"));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).containsExactly("refresh: my.ext: IllegalArgumentException: wiring");
        assertThat(report.log()).contains("refresh finished", "context /polarion/my.ext added");
        assertThat(frameworkListeners).isEmpty();
    }

    @Test
    void putsTheBundlesBackWhenAFrameworkChangeFails() throws Exception {
        Bundle failing = extension("my.failing", 10);
        Bundle other = extension("my.other", 11);
        doAnswer(invocation -> {
            throw new BundleException("broken jar");
        }).when(failing).update();
        bundleJars.put(failing, Files.writeString(tempDir.resolve("my.failing.jar"), "new failing"));
        bundleJars.put(other, Files.writeString(tempDir.resolve("my.other.jar"), "new other"));
        closure = List.of(failing, other);
        changes = List.of(
                new BundleChange(BundleChange.Type.UPDATE, "my.failing", jarPath("my.failing.jar"), failing),
                new BundleChange(BundleChange.Type.UPDATE, "my.other", jarPath("my.other.jar"), other));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).containsExactly("BundleException: broken jar");
        assertThat(report.log()).contains(
                "context /polarion/my.failing removed",
                "context /polarion/my.other removed",
                "refresh finished",
                "context /polarion/my.failing added",
                "context /polarion/my.other added");
        verify(injectors.constructed().get(0)).finish(Set.of());
        // Neither change ran, so both stay changes for the next rescan.
        assertThat(JarFingerprints.get(failing)).isNull();
        assertThat(JarFingerprints.get(other)).isNull();
    }

    @Test
    void putsTheOtherBundlesBackWhenOneFailsToComeBack() throws Exception {
        Bundle broken = extension("my.broken", 10);
        Bundle other = extension("my.other", 11);
        when(broken.getHeaders()).thenReturn(headers("Bundle-ActivationPolicy", "lazy"));
        doAnswer(invocation -> {
            throw new BundleException("activator failed");
        }).when(broken).start(Bundle.START_ACTIVATION_POLICY);
        closure = List.of(broken, other);
        Path brokenJar = Files.writeString(tempDir.resolve("my.broken.jar"), "new broken");
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.broken", brokenJar, broken));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).containsExactly("my.broken: BundleException: activator failed");
        assertThat(report.log()).contains("context /polarion/my.other added").doesNotContain("context /polarion/my.broken added");
        verify(injectors.constructed().get(0)).finish(Set.of("my.broken"));
        // Stays a change, so the next rescan tries it again.
        assertThat(JarFingerprints.get(broken)).isNotNull().isNotEqualTo(JarFingerprints.of(brokenJar));
    }

    @Test
    void keepsADependentThatFailsToComeBackAsAChange() throws Exception {
        Bundle updated = extension("my.ext", 10);
        Bundle dependent = extension("my.dependent", 11);
        when(dependent.getHeaders()).thenReturn(headers("Bundle-ActivationPolicy", "lazy"));
        doAnswer(invocation -> {
            throw new BundleException("activator failed");
        }).when(dependent).start(Bundle.START_ACTIVATION_POLICY);
        Path dependentJar = Files.writeString(tempDir.resolve("my.dependent.jar"), "dependent");
        JarFingerprints.put(dependent, dependentJar);
        closure = List.of(updated, dependent);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jarPath("my.ext.jar"), updated));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).containsExactly("my.dependent: BundleException: activator failed");
        verify(injectors.constructed().get(0)).finish(Set.of("my.dependent"));
        assertThat(JarFingerprints.get(dependent)).isNotEqualTo(JarFingerprints.of(dependentJar));
    }

    @Test
    void keepsTheWorkflowHoldersWhenAReplacementFailsToInstall() throws Exception {
        Path replacementJar = jarPath("my.replaced-2.0.jar");
        Bundle replaced = extension("my.replaced", 10);
        doAnswer(invocation -> states.put(replaced, Bundle.UNINSTALLED)).when(replaced).uninstall();
        when(context.installBundle(ExtensionScanner.installLocation(replacementJar))).thenThrow(new BundleException("broken jar"));
        closure = List.of(replaced);
        changes = List.of(new BundleChange(BundleChange.Type.REPLACE, "my.replaced", replacementJar, replaced));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).containsExactly("BundleException: broken jar");
        verify(injectors.constructed().get(0)).finish(Set.of("my.replaced"));
    }

    @Test
    void removesTheWorkflowHoldersOfAFailedInstallWhoseJarIsGone() {
        PendingWorkflowHolders.add("my.gone", Set.of(new Object()));
        PendingWorkflowHolders.add("my.waiting", Set.of(new Object()));
        scannedNames.add("my.waiting");

        RescanReport dryRun = new RedeployService().rescan(false);

        assertThat(dryRun.changes()).containsExactly("CLEANUP my.gone (workflow contributions or job factories of a failed install)");
        assertThat(injectors.constructed()).isEmpty();

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.applied()).isTrue();
        assertThat(report.errors()).isEmpty();
        verify(injectors.constructed().get(0)).finish(Set.of("my.waiting"));
    }

    @Test
    void keepsTheWorkflowHoldersWhileAJarCannotBeRead() {
        PendingWorkflowHolders.add("my.copying", Set.of(new Object()));
        unreadableJars = true;

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.changes()).isEmpty();
        assertThat(PendingWorkflowHolders.symbolicNames()).containsExactly("my.copying");
    }

    @Test
    void keepsTheWorkflowHoldersOfAnotherBundleThroughARescan() throws Exception {
        PendingWorkflowHolders.add("my.copying", Set.of(new Object()));
        unreadableJars = true;
        Bundle updated = extension("my.ext", 10);
        closure = List.of(updated);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jarPath("my.ext.jar"), updated));

        new RedeployService().rescan(true);

        verify(injectors.constructed().get(0)).finish(Set.of("my.copying"));
    }

    @Test
    void recordsTheFingerprintOfTheJarTheUpdateRead() throws Exception {
        Bundle updated = extension("my.ext", 10);
        Path jar = Files.writeString(tempDir.resolve("my.ext.jar"), "applied build");
        String applied = JarFingerprints.of(jar);
        doAnswer(invocation -> Files.writeString(jar, "later build")).when(updated).update();
        closure = List.of(updated);
        changes = List.of(new BundleChange(BundleChange.Type.UPDATE, "my.ext", jar, updated));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).isEmpty();
        assertThat(JarFingerprints.get(updated)).isEqualTo(applied);
    }

    @Test
    void startsAReplacementOnlyWhenItsPredecessorWasStarted() throws Exception {
        Path startedJar = jarPath("my.started-2.0.jar");
        Path resolvedJar = jarPath("my.resolved-2.0.jar");
        Bundle started = extension("my.started", 10);
        Bundle resolved = extension("my.resolved", 11);
        states.put(started, Bundle.ACTIVE);
        Bundle startedReplacement = extension("my.started", 20);
        Bundle resolvedReplacement = extension("my.resolved", 21);
        when(context.installBundle(ExtensionScanner.installLocation(startedJar))).thenReturn(startedReplacement);
        when(context.installBundle(ExtensionScanner.installLocation(resolvedJar))).thenReturn(resolvedReplacement);
        doAnswer(invocation -> states.put(started, Bundle.UNINSTALLED)).when(started).uninstall();
        doAnswer(invocation -> states.put(resolved, Bundle.UNINSTALLED)).when(resolved).uninstall();
        closure = List.of(started, resolved);
        changes = List.of(
                new BundleChange(BundleChange.Type.REPLACE, "my.started", startedJar, started),
                new BundleChange(BundleChange.Type.REPLACE, "my.resolved", resolvedJar, resolved));

        RescanReport report = new RedeployService().rescan(true);

        assertThat(report.errors()).isEmpty();
        verify(startedReplacement).start(Bundle.START_ACTIVATION_POLICY);
        verify(resolvedReplacement, never()).start(anyInt());
    }

    @Test
    void asksForARestartForANewJarOfItself() {
        ownJarChanged = true;

        RescanReport report = new RedeployService().rescan(false);

        assertThat(report.restartRequired())
                .containsExactly("my.self: this extension cannot reload itself, restart Polarion to use its new jar");
        assertThat(report.log()).containsExactly("nothing changed");
    }

    @Test
    void logsTheScannerWarnings() {
        scannerWarnings.add("two jars for my.ext: a.jar and b.jar, using b.jar; remove the other one");

        RescanReport report = new RedeployService().rescan(false);

        assertThat(report.log()).containsExactly("WARNING two jars for my.ext: a.jar and b.jar, using b.jar; remove the other one", "nothing changed");
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
                "my.new does not resolve; check its Require-Bundle and Import-Package");
        verify(injectors.constructed().get(0)).finish(Set.of("my.new"));
        // Without a fingerprint the scanner would compare file times, and a jar copied before the install is not newer.
        assertThat(JarFingerprints.get(installed)).isNotNull();
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
        // As in Polarion: this extension lives in the extensions folder too.
        selfInExtensions = true;
        bundleJars.put(self, Files.writeString(tempDir.resolve("my.self.jar"), "self"));
        Bundle extension = extension("my.ext", 10);
        Bundle broken = extension("my.broken", 11);
        when(broken.getEntry("plugin.xml")).thenReturn(file("broken.xml", "<plugin>"));
        Bundle gone = extension("my.gone", 12);
        states.put(gone, Bundle.UNINSTALLED);
        bundleJars.put(extension, Files.writeString(tempDir.resolve("my.ext.jar"), "content"));
        bundleJars.put(broken, Files.writeString(tempDir.resolve("my.broken.jar"), "broken"));

        try (MockedStatic<ContributionInjector> lazy = mockStatic(ContributionInjector.class)) {
            lazy.when(ContributionInjector::buildLazyServices).thenThrow(new IllegalStateException("no tracker"));

            new RedeployService().snapshotInstalledExtensions();
        }

        assertThat(DescriptorCache.get(extension)).isNotNull();
        assertThat(JarFingerprints.get(extension)).isNotNull();
        assertThat(DescriptorCache.get(broken)).isNull();
        // The hash still detects a later build of a jar whose descriptors cannot be read.
        assertThat(JarFingerprints.get(broken)).isNotNull();
        assertThat(DescriptorCache.get(gone)).isNull();
        // Its hash tells whether a new jar of it waits for a restart.
        assertThat(JarFingerprints.get(self)).isEqualTo(JarFingerprints.of(tempDir.resolve("my.self.jar")));
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
