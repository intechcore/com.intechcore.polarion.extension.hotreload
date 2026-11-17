package com.intechcore.polarion.extension.hotreload.inject;

import com.intechcore.polarion.extension.hotreload.descriptor.ExtensionDescriptor;
import com.intechcore.polarion.extension.hotreload.internal.Reflect;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Removes and re-adds Tomcat contexts of {@code com.polarion.portal.tomcat.webapps} contributions.
 * <p>
 * A context keeps the class loader of the bundle wiring it was created with, so after a bundle refresh only a new
 * context serves the new code. {@code TomcatAppServer.add} already supports adding a context after startup.
 */
public class TomcatDeployer {

    private static final String TOMCAT_BUNDLE = "com.polarion.portal.tomcat";
    private static final String PLATFORM_BUNDLE = "com.polarion.platform";

    private final Object appServer;
    private final Object host;

    public TomcatDeployer() {
        Class<?> appServerClass = Reflect.loadClass(TOMCAT_BUNDLE, "com.polarion.portal.tomcat.TomcatAppServer");
        this.appServer = Reflect.callStatic(appServerClass, "instance");
        Object tomcat = Reflect.getField(appServer, "tomcat");
        this.host = Reflect.call(tomcat, "getHost");
    }

    /**
     * @return {@code true} when a context was found and removed
     */
    public boolean remove(@NotNull String webappName) {
        Object context = Reflect.call(host, "findChild", contextPath(webappName));
        if (context == null) {
            return false;
        }
        Object workDir = Reflect.call(context, "getWorkDir");
        Reflect.call(host, "removeChild", context);
        if (workDir instanceof String path && !path.isBlank()) {
            deleteRecursively(resolveWorkDir(path));
        }
        return true;
    }

    /**
     * @return {@code true} when Tomcat accepted and started the new context
     */
    public boolean add(@NotNull ExtensionDescriptor.Webapp webapp, @NotNull String bundleSymbolicName) {
        Class<?> configFactoryClass = Reflect.loadClass(PLATFORM_BUNDLE, "com.polarion.platform.servlet.PolarionTomcatConfigFactory");
        Class<?> contributionClass = Reflect.loadClass(TOMCAT_BUNDLE, "com.polarion.portal.tomcat.WebAppContribution");
        Object contribution = Reflect.newInstance(contributionClass,
                webapp.name(), webapp.contextRoot(), bundleSymbolicName, 0, Reflect.newInstance(configFactoryClass));

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            // TomcatAppServer.startAfterAdddingWebApps does the same, so JULI and Jasper resolve against Tomcat.
            thread.setContextClassLoader(appServer.getClass().getClassLoader());
            return Boolean.TRUE.equals(Reflect.call(appServer, "add", contribution));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @NotNull
    private static String contextPath(@NotNull String webappName) {
        return "/" + webappName;
    }

    @NotNull
    private Path resolveWorkDir(@NotNull String workDir) {
        File file = new File(workDir);
        if (file.isAbsolute()) {
            return file.toPath();
        }
        String catalinaBase = System.getProperty("catalina.base", "");
        return Path.of(catalinaBase, workDir);
    }

    private static void deleteRecursively(@NotNull Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete Tomcat work directory " + path, e);
        }
    }
}
