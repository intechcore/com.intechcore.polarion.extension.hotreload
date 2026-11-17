package com.intechcore.polarion.extension.hotreload.service;

import com.intechcore.polarion.extension.hotreload.descriptor.DescriptorParser;
import com.intechcore.polarion.extension.hotreload.descriptor.ExtensionDescriptor;
import com.intechcore.polarion.extension.hotreload.inject.ContributionInjector;
import com.intechcore.polarion.extension.hotreload.inject.TomcatDeployer;
import com.polarion.core.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * Brings the running framework in line with the extensions folder, without a restart.
 * <ol>
 *     <li>Find changed jars and the bundles that depend on them.</li>
 *     <li>Take their Tomcat contexts and contributions out, while the old class loaders still work.</li>
 *     <li>Install, update or uninstall, then refresh the dependency closure.</li>
 *     <li>Put the contexts and contributions back from the new bundle revisions.</li>
 * </ol>
 * Step 4 runs also after a failure in step 3, for every bundle that resolves, so a failed rescan leaves as much
 * running as it can.
 */
public class RedeployService {

    private static final Logger logger = Logger.getLogger(RedeployService.class);

    private static final int EXTENSION_START_LEVEL = 4;
    private static final long REFRESH_TIMEOUT_SECONDS = 120;
    private static final String ADMIN_MENU_ORGANIZER = "com.intechcore.polarion.extension.admin-menu-organizer";

    private static final Object LOCK = new Object();

    private final BundleContext bundleContext;
    private final Bundle self;

    public RedeployService() {
        this.self = FrameworkUtil.getBundle(RedeployService.class);
        this.bundleContext = self.getBundleContext();
    }

    @NotNull
    public RescanReport rescan(boolean apply) {
        synchronized (LOCK) {
            List<String> log = new ArrayList<>();
            List<String> restartRequired = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            ExtensionScanner scanner = new ExtensionScanner(bundleContext);
            List<BundleChange> changes = scanner.scan(self.getSymbolicName());
            List<String> changeLines = changes.stream().map(BundleChange::toString).toList();
            scanner.getWarnings().forEach(w -> log.add("WARNING " + w));

            if (!apply || changes.isEmpty()) {
                if (changes.isEmpty()) {
                    log.add("nothing changed");
                }
                return new RescanReport(false, changeLines, log, restartRequired, errors);
            }
            try {
                apply(changes, scanner, log, restartRequired, errors);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                logger.error("Hot redeploy failed", e);
                errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            log.forEach(line -> logger.info("hotreload: " + line));
            return new RescanReport(true, changeLines, log, restartRequired, errors);
        }
    }

    private void apply(@NotNull List<BundleChange> changes, @NotNull ExtensionScanner scanner, @NotNull List<String> log,
                       @NotNull List<String> restartRequired, @NotNull List<String> errors) throws BundleException, InterruptedException {
        FrameworkWiring frameworkWiring = bundleContext.getBundle(0).adapt(FrameworkWiring.class);

        List<Bundle> existing = changes.stream().map(BundleChange::bundle).filter(Objects::nonNull).toList();
        Collection<Bundle> closure = existing.isEmpty() ? List.of() : frameworkWiring.getDependencyClosure(existing);
        for (Bundle bundle : closure) {
            if (bundle.equals(self) || !scanner.isExtensionBundle(bundle)) {
                throw new IllegalStateException("Refresh would reach " + bundle.getSymbolicName()
                        + ", which is not an extension bundle; restart Polarion instead");
            }
        }
        List<String> dependents = closure.stream().filter(b -> !existing.contains(b)).map(Bundle::getSymbolicName).toList();
        if (!dependents.isEmpty()) {
            log.add("dependent bundles refreshed too: " + dependents);
        }

        TomcatDeployer tomcat = new TomcatDeployer();
        ContributionInjector injector = new ContributionInjector(log);
        Map<String, Path> newJars = new HashMap<>();
        changes.stream().filter(c -> c.jar() != null).forEach(c -> newJars.put(c.symbolicName(), c.jar()));

        // 1. take the old revisions out
        boolean adminPagesChanged = false;
        for (Bundle bundle : closure) {
            ExtensionDescriptor old = oldDescriptor(bundle, newJars.get(bundle.getSymbolicName()), log);
            for (ExtensionDescriptor.Webapp webapp : old.getWebapps()) {
                if (tomcat.remove(webapp.name())) {
                    log.add("context /" + webapp.name() + " removed");
                }
            }
            injector.remove(old, bundle.getSymbolicName(), classLoader(bundle));
            DescriptorCache.remove(bundle);
            adminPagesChanged |= !old.getAdministrationPages().isEmpty();
        }

        // 2. change the framework; a failure here still lets step 3 put back what resolves
        List<Bundle> installedNow = new ArrayList<>();
        Set<Bundle> toStart = new LinkedHashSet<>();
        Map<Bundle, String> applied = new HashMap<>();
        Exception failure = null;
        try {
            changeFramework(changes, installedNow, toStart, applied, log);
        } catch (BundleException | RuntimeException e) {
            failure = e;
        }
        // A bundle stays a change until it is put back, also when it never resolves.
        applied.keySet().forEach(JarFingerprints::markUnfinished);
        if (!existing.isEmpty()) {
            // A timeout or an interrupt propagates: the refresh still runs, so nothing can be put back safely.
            for (String error : refresh(frameworkWiring, existing)) {
                errors.add("refresh: " + error);
            }
            log.add("refresh finished");
        }

        Set<Bundle> targets = new LinkedHashSet<>();
        for (Bundle bundle : closure) {
            if (bundle.getState() != Bundle.UNINSTALLED) {
                targets.add(bundle);
            } else {
                JarFingerprints.remove(bundle);
            }
        }
        targets.addAll(installedNow);
        frameworkWiring.resolveBundles(targets);

        // 3. put the new revisions back
        Set<String> unfinished = new HashSet<>();
        for (Bundle bundle : targets) {
            if (bundle.getState() == Bundle.INSTALLED) {
                errors.add(bundle.getSymbolicName() + " does not resolve; check its Require-Bundle and Import-Package");
                unfinished.add(bundle.getSymbolicName());
                JarFingerprints.markUnfinished(bundle);
                continue;
            }
            try {
                start(bundle, toStart.contains(bundle));
                adminPagesChanged |= putBack(bundle, tomcat, injector, log, restartRequired);
                // The hash of the jar the change read, not of the file now: another build can land meanwhile.
                String fingerprint = applied.get(bundle);
                if (fingerprint != null) {
                    JarFingerprints.put(bundle, fingerprint);
                }
            } catch (BundleException | RuntimeException e) {
                logger.error("Hot redeploy of " + bundle.getSymbolicName() + " failed", e);
                errors.add(bundle.getSymbolicName() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                unfinished.add(bundle.getSymbolicName());
                // Also a dependent that did not change: the next scan reports it, so a rescan brings it back.
                JarFingerprints.markUnfinished(bundle);
            }
        }
        injector.finish(unfinished);

        if (adminPagesChanged) {
            reapplyAdminMenuOrder(tomcat, targets, log);
        }
        if (failure instanceof BundleException bundleException) {
            throw bundleException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
    }

    private void changeFramework(@NotNull List<BundleChange> changes, @NotNull List<Bundle> installedNow,
                                 @NotNull Set<Bundle> toStart, @NotNull Map<Bundle, String> applied, @NotNull List<String> log) throws BundleException {
        for (BundleChange change : changes) {
            switch (change.type()) {
                case UNINSTALL -> {
                    change.bundle().uninstall();
                    log.add(change.symbolicName() + ": uninstalled");
                }
                case UPDATE -> {
                    String fingerprint = fingerprint(change.jar());
                    change.bundle().update();
                    applied.put(change.bundle(), fingerprint);
                    log.add(change.symbolicName() + ": updated in place");
                }
                case REPLACE -> {
                    boolean wasStarted = isStarted(change.bundle());
                    String fingerprint = fingerprint(change.jar());
                    change.bundle().uninstall();
                    Bundle replacement = install(change.jar());
                    installedNow.add(replacement);
                    applied.put(replacement, fingerprint);
                    if (wasStarted) {
                        toStart.add(replacement);
                    }
                    log.add(change.symbolicName() + ": replaced by " + change.jar().getFileName());
                }
                case INSTALL -> {
                    String fingerprint = fingerprint(change.jar());
                    Bundle installed = install(change.jar());
                    installedNow.add(installed);
                    applied.put(installed, fingerprint);
                    log.add(change.symbolicName() + ": installed");
                }
            }
        }
    }

    /**
     * Hashes the jar just before the framework reads it.
     *
     * @return {@code ""} when the jar cannot be read: it matches no content, so the bundle stays a change
     */
    @NotNull
    private static String fingerprint(@NotNull Path jar) {
        try {
            return JarFingerprints.of(jar);
        } catch (IllegalStateException e) {
            return "";
        }
    }

    /**
     * @return whether the bundle contributes administration pages
     */
    private static boolean putBack(@NotNull Bundle bundle, @NotNull TomcatDeployer tomcat, @NotNull ContributionInjector injector,
                                   @NotNull List<String> log, @NotNull List<String> restartRequired) {
        ExtensionDescriptor descriptor = descriptor(bundle);
        DescriptorCache.put(bundle, descriptor);
        descriptor.getRestartRequiredReasons().forEach(r -> restartRequired.add(bundle.getSymbolicName() + ": " + r));
        descriptor.getWarnings().forEach(w -> log.add("WARNING " + bundle.getSymbolicName() + ": " + w));
        for (ExtensionDescriptor.Webapp webapp : descriptor.getWebapps()) {
            if (webapp.hasErrorReporter()) {
                log.add("WARNING context /" + webapp.name() + ": errorReporter is not re-registered");
            }
            boolean added = tomcat.add(webapp, bundle.getSymbolicName());
            log.add("context /" + webapp.name() + (added ? " added" : " NOT added, see the Polarion log"));
        }
        injector.add(descriptor, bundle);
        return !descriptor.getAdministrationPages().isEmpty();
    }

    @NotNull
    private Bundle install(@NotNull Path jar) throws BundleException {
        Bundle bundle = bundleContext.installBundle(ExtensionScanner.installLocation(jar));
        bundle.adapt(BundleStartLevel.class).setStartLevel(EXTENSION_START_LEVEL);
        return bundle;
    }

    /**
     * Waits for the refresh. The listener given to {@code refreshBundles} gets {@code PACKAGES_REFRESHED} only, so
     * the errors of the refresh, such as an activator that fails on restart, come from a framework listener.
     *
     * @return the errors published during the refresh
     */
    @NotNull
    private List<String> refresh(@NotNull FrameworkWiring frameworkWiring, @NotNull Collection<Bundle> bundles) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        FrameworkListener errorListener = event -> {
            if (event.getType() == FrameworkEvent.ERROR) {
                Throwable error = event.getThrowable();
                String source = event.getBundle() != null ? event.getBundle().getSymbolicName() + ": " : "";
                failures.add(source + (error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "error"));
            }
        };
        bundleContext.addFrameworkListener(errorListener);
        try {
            frameworkWiring.refreshBundles(bundles, event -> {
                if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED) {
                    done.countDown();
                }
            });
            if (!done.await(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Bundle refresh did not finish in " + REFRESH_TIMEOUT_SECONDS + " s");
            }
        } finally {
            bundleContext.removeFrameworkListener(errorListener);
        }
        synchronized (failures) {
            return new ArrayList<>(failures);
        }
    }

    /**
     * Starts a lazy bundle the way the startup does, and a replacement whose predecessor was started. Polarion
     * installs extension bundles without a start flag, so a new eager bundle stays resolved, as after a restart.
     */
    private static void start(@NotNull Bundle bundle, boolean predecessorStarted) throws BundleException {
        if (bundle.getState() != Bundle.RESOLVED) {
            return;
        }
        String policy = bundle.getHeaders().get("Bundle-ActivationPolicy");
        if (predecessorStarted || (policy != null && policy.startsWith("lazy"))) {
            bundle.start(Bundle.START_ACTIVATION_POLICY);
        }
    }

    private static boolean isStarted(@NotNull Bundle bundle) {
        return bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.STARTING;
    }

    /**
     * admin-menu-organizer applies its menu order from a servlet context listener, so a fresh context re-applies it.
     */
    private void reapplyAdminMenuOrder(@NotNull TomcatDeployer tomcat, @NotNull Set<Bundle> alreadyRedeployed, @NotNull List<String> log) {
        for (Bundle bundle : bundleContext.getBundles()) {
            if (ADMIN_MENU_ORGANIZER.equals(bundle.getSymbolicName()) && !alreadyRedeployed.contains(bundle)) {
                for (ExtensionDescriptor.Webapp webapp : descriptor(bundle).getWebapps()) {
                    tomcat.remove(webapp.name());
                    tomcat.add(webapp, bundle.getSymbolicName());
                }
                log.add("admin-menu-organizer contexts restarted to re-apply the menu order");
            }
        }
    }

    /**
     * Reads the descriptors of all installed extension bundles, so a later uninstall knows what to take out even
     * when the jar is already gone. Called once when this extension's web application starts.
     */
    public void snapshotInstalledExtensions() {
        try {
            ContributionInjector.buildLazyServices();
        } catch (RuntimeException e) {
            logger.warn("hotreload: cannot build the workflow and navigation services in advance", e);
        }
        ExtensionScanner scanner = new ExtensionScanner(bundleContext);
        for (Bundle bundle : bundleContext.getBundles()) {
            if (bundle.getState() != Bundle.UNINSTALLED && !bundle.equals(self) && scanner.isExtensionBundle(bundle)) {
                try {
                    DescriptorCache.put(bundle, descriptor(bundle));
                    Path jar = scanner.jarOf(bundle);
                    if (jar != null && Files.isRegularFile(jar)) {
                        JarFingerprints.put(bundle, jar);
                    }
                } catch (RuntimeException e) {
                    logger.warn("hotreload: cannot read descriptors of " + bundle.getSymbolicName(), e);
                }
            }
        }
    }

    /**
     * The snapshot taken while the old jar still existed tells what to take out. Without one, the current files
     * are read; once the jar is overwritten that can fail, so the new jar is the last fallback: keys rarely change
     * between two builds, and object values are also matched by class loader.
     */
    @NotNull
    private ExtensionDescriptor oldDescriptor(@NotNull Bundle bundle, @Nullable Path newJar, @NotNull List<String> log) {
        ExtensionDescriptor cached = DescriptorCache.get(bundle);
        if (cached != null) {
            return cached;
        }
        log.add("WARNING " + bundle.getSymbolicName() + ": no descriptor snapshot, reading the current files");
        try {
            return descriptor(bundle);
        } catch (RuntimeException e) {
            if (newJar == null) {
                throw e;
            }
            log.add("WARNING " + bundle.getSymbolicName() + ": old descriptors unreadable (" + e.getMessage() + "), using the new jar");
            return descriptor(newJar);
        }
    }

    @NotNull
    private static ExtensionDescriptor descriptor(@NotNull Bundle bundle) {
        try (InputStream pluginXml = open(bundle.getEntry("plugin.xml"));
             InputStream hivemodule = open(bundle.getEntry("META-INF/hivemodule.xml"))) {
            return DescriptorParser.parse(pluginXml, hivemodule, bundle.getHeaders().get("Guice-Modules"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read descriptors of " + bundle.getSymbolicName(), e);
        }
    }

    @NotNull
    private static ExtensionDescriptor descriptor(@NotNull Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Map<String, InputStream> streams = new LinkedHashMap<>();
            for (String entry : List.of("plugin.xml", "META-INF/hivemodule.xml")) {
                streams.put(entry, jarFile.getEntry(entry) != null ? jarFile.getInputStream(jarFile.getEntry(entry)) : null);
            }
            String guiceModules = jarFile.getManifest() != null ? jarFile.getManifest().getMainAttributes().getValue("Guice-Modules") : null;
            return DescriptorParser.parse(streams.get("plugin.xml"), streams.get("META-INF/hivemodule.xml"), guiceModules);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read descriptors of " + jar, e);
        }
    }

    @Nullable
    private static InputStream open(@Nullable URL url) throws IOException {
        return url != null ? url.openStream() : null;
    }

    @Nullable
    private static ClassLoader classLoader(@NotNull Bundle bundle) {
        BundleWiring wiring = bundle.adapt(BundleWiring.class);
        return wiring != null ? wiring.getClassLoader() : null;
    }
}
